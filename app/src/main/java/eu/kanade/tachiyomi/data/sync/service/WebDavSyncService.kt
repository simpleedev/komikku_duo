package eu.kanade.tachiyomi.data.sync.service

// KMK -->
import android.content.Context
import android.util.AtomicFile
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.network.await
import exh.log.xLogD
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class WebDavSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
    private val notifier: SyncNotifier,
    private val protoBuf: ProtoBuf = Injekt.get(),
) : SyncService(context, json, syncPreferences) {
    private val settings = syncPreferences.getSyncSettings()
    private val merger = WebDavSyncMerger(settings)
    private val base = syncPreferences.webDavUrl().get().trim().toHttpUrl()
    private val folder = syncPreferences.webDavFolder().get().trim('/')
    private val url = base.newBuilder().apply {
        if (!base.encodedPath.endsWith('/')) addPathSegment("")
        folder.split('/').filter { it.isNotEmpty() }.forEach(::addPathSegment)
        addPathSegment("backup.proto")
    }.build()
    private val credentials = Credentials.basic(
        syncPreferences.webDavUsername().get().trim(),
        syncPreferences.webDavPassword().get(),
    )
    // Scope validators and baselines to the account, endpoint and selected sections.
    private val scope = WebDavSyncMerger.digest("$url|${syncPreferences.webDavUsername().get()}|$settings")
    private val directory = File(context.noBackupFilesDir, "webdav-sync/$scope").apply { mkdirs() }
    private val checkpointFile = AtomicFile(File(directory, "checkpoint.proto"))
    private val legacyCheckpointFile = AtomicFile(File(directory, "checkpoint.json"))
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val transport = HttpWebDavTransport(client, url.toString(), credentials, protoBuf, ::ensureFolderExists)

    override suspend fun doSync(syncData: SyncData): Backup? =
        error("WebDAV must await local application before saving its checkpoint")

    suspend fun synchronize(local: Backup, restore: suspend (Backup) -> Backup) {
        var remoteBefore: Backup? = null
        var legacyCheckpointLoaded = false
        val checkpointStarted = System.nanoTime()
        val checkpoint = try {
            try {
                protoBuf.decodeFromByteArray(WebDavSyncCheckpoint.serializer(), checkpointFile.readFully())
            } catch (_: FileNotFoundException) {
                // Migrate existing ancestry without holding a full JSON byte array and String.
                // Keep the legacy file intact; the next successful sync atomically writes binary.
                legacyCheckpointFile.openRead().use {
                    json.decodeFromStream(WebDavSyncCheckpoint.serializer(), it)
                }.also { legacyCheckpointLoaded = true }
            }
        } catch (e: Exception) {
            // A missing/old/damaged checkpoint only disables the optimization, never deletes data.
            xLogD("WebDAV checkpoint unavailable (%s); reading remote", e.javaClass.simpleName)
            null
        } finally {
            xLogD("WebDAV stage=checkpoint durationMs=%s", (System.nanoTime() - checkpointStarted) / 1_000_000)
        }
        try {
            repeat(3) { attempt ->
                try {
                    WebDavSyncEngine(transport, merger) { xLogD("WebDAV %s", it) }.sync(
                        local,
                        checkpoint,
                        restore,
                        protect = { before, remote ->
                            remoteBefore = remote
                            saveRecovery("local", before)
                            saveRecovery("remote", remote)
                            if (checkpoint == null && merger.revision(before) != merger.revision(remote)) {
                                // Preserve bootstrap alternatives even after the rotating recovery files advance.
                                saveRecovery("bootstrap-local-${merger.revision(before)}", before)
                                saveRecovery("bootstrap-remote-${merger.revision(remote)}", remote)
                                xLogD("WebDAV bootstrap retained both original snapshots for recovery")
                            }
                        },
                        commit = {
                            atomicWrite(checkpointFile, protoBuf.encodeToByteArray(WebDavSyncCheckpoint.serializer(), it))
                            legacyCheckpointLoaded = false
                        },
                    )
                    // A 304 no-op does not invoke the engine's commit callback. Its acknowledged
                    // ancestry is still safe to migrate, avoiding a large JSON parse next launch.
                    if (legacyCheckpointLoaded && checkpoint != null) {
                        atomicWrite(checkpointFile, protoBuf.encodeToByteArray(WebDavSyncCheckpoint.serializer(), checkpoint))
                    }
                    syncPreferences.lastSyncTimestamp().set(System.currentTimeMillis())
                    return
                } catch (e: WebDavPreconditionException) {
                    xLogD("WebDAV concurrent writer; re-read and merge attempt=%s", attempt + 1)
                    if (attempt == 2) throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is WebDavConflictException || e is WebDavPreconditionException) {
                saveRecovery("conflict-local-${merger.revision(local)}", local)
                remoteBefore?.let { saveRecovery("conflict-remote-${merger.revision(it)}", it) }
            }
            // Server bodies, URLs and throwable messages can contain credentials or user content.
            xLogD("WebDAV failed type=%s", e.javaClass.simpleName)
            if (e is WebDavConflictException) xLogD("WebDAV conflict field=%s", e.field ?: "unknown")
            val message = when (e) {
                is WebDavUnsafeWriteException -> KMR.strings.webdav_sync_unsafe_server
                is WebDavConflictException, is WebDavPreconditionException -> KMR.strings.webdav_sync_conflict
                else -> KMR.strings.webdav_sync_failed
            }
            notifier.showSyncError(context.stringResource(message))
            throw WebDavSyncFailure()
        }
    }

    private fun saveRecovery(side: String, backup: Backup) {
        // These may contain private settings; keep them in app-private, non-cloud-backed storage.
        val file = File(directory, "$side.proto")
        atomicWrite(AtomicFile(file), protoBuf.encodeToByteArray(Backup.serializer(), backup))
    }

    private suspend fun ensureFolderExists() {
        val builder = base.newBuilder().apply { if (!base.encodedPath.endsWith('/')) addPathSegment("") }
        folder.split('/').filter { it.isNotEmpty() }.forEach { part ->
            builder.addPathSegment(part)
            val request = Request.Builder().url(builder.build()).method("MKCOL", null)
                .header("Authorization", credentials).build()
            client.newCall(request).await().use {
                if (!it.isSuccessful && it.code != 405) throw WebDavHttpException(it.code)
            }
        }
    }

    private fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}

/** The localized failure has already been shown; do not replace it with a raw exception message. */
class WebDavSyncFailure : Exception()
// KMK <--
