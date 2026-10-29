package eu.kanade.tachiyomi.data.sync.service

// KMK -->
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.io.IOException

/** The checkpoint is private, endpoint/options scoped, and written only after local application succeeds. */
@Serializable
data class WebDavSyncCheckpoint(val local: Backup, val remote: Backup, val etag: String?)

sealed interface WebDavRemote {
    data object Missing : WebDavRemote
    data object Unchanged : WebDavRemote
    data class Found(val backup: Backup, val etag: String?) : WebDavRemote
}

interface WebDavTransport {
    suspend fun read(etag: String?): WebDavRemote
    suspend fun write(backup: Backup, previous: WebDavRemote): String?
}

class WebDavConflictException : IOException("Concurrent WebDAV changes")
class WebDavUnsafeWriteException : IOException("WebDAV has no strong ETag for a safe update")

/** No Android dependencies: exercises the same decisions and failure boundaries in unit tests. */
class WebDavSyncEngine(
    private val transport: WebDavTransport,
    private val merger: WebDavSyncMerger = WebDavSyncMerger(),
    private val log: (String) -> Unit = {},
) {
    suspend fun sync(
        local: Backup,
        checkpoint: WebDavSyncCheckpoint?,
        restore: suspend (Backup) -> Backup,
        protect: suspend (Backup, Backup) -> Unit,
        commit: (WebDavSyncCheckpoint) -> Unit,
    ) {
        val localRevision = merger.revision(local)
        val started = System.nanoTime()
        // Only a strong, quoted entity tag associated with a cached body can prove equality.
        val previous = stage("check/download") { transport.read(checkpoint?.etag?.takeIf(::isStrongEtag)) }
        val remote = when (previous) {
            WebDavRemote.Unchanged -> checkNotNull(checkpoint).remote
            WebDavRemote.Missing -> null
            is WebDavRemote.Found -> previous.backup
        }
        val localChanged = checkpoint == null || localRevision != merger.revision(checkpoint.local)
        val remoteChanged = remote == null || checkpoint == null ||
            merger.revision(remote) != merger.revision(checkpoint.remote)
        log(
            "local=$localRevision remote=${remote?.let(merger::revision)} localChanged=$localChanged " +
                "remoteChanged=$remoteChanged metadata=${previous::class.simpleName}",
        )

        if (!localChanged && !remoteChanged) {
            log("decision=no-op restore=false")
            if (previous is WebDavRemote.Found) {
                commit(WebDavSyncCheckpoint(local, previous.backup, previous.etag?.takeIf(::isStrongEtag)))
            }
            return
        }

        val merged = if (remote == null) {
            merger.forUpload(local)
        } else {
            // Preserve recovery copies before any merge, upload or restore can replace a value.
            stage("recovery") { protect(local, remote) }
            stage("merge") { merger.merge(local, remote, checkpoint) }
        }
        val upload = remote == null || merger.revision(merged) != merger.revision(remote)
        val needsRestore = merger.revision(merger.forRestore(merged)) != merger.revision(local)
        log("decision upload=$upload restore=$needsRestore")
        val etag = if (upload) {
            val expected = if (previous == WebDavRemote.Unchanged) {
                WebDavRemote.Found(checkNotNull(remote), checkpoint?.etag)
            } else {
                previous
            }
            stage("upload") { transport.write(merged, expected) }
        } else {
            if (previous is WebDavRemote.Found) previous.etag else checkpoint?.etag
        }
        currentCoroutineContext().ensureActive()
        val applied = if (needsRestore) stage("restore") { restore(merger.forRestore(merged)) } else local
        currentCoroutineContext().ensureActive()
        commit(WebDavSyncCheckpoint(applied, merged, etag?.takeIf(::isStrongEtag)))
        log("complete durationMs=${(System.nanoTime() - started) / 1_000_000}")
    }

    private suspend fun <T> stage(name: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            log("stage=$name durationMs=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    companion object {
        fun isStrongEtag(value: String): Boolean =
            value.length >= 2 && value.startsWith('"') && value.endsWith('"') &&
                value.substring(1, value.lastIndex).all { it.code == 0x21 || it.code in 0x23..0x7e || it.code in 0x80..0xff }
    }
}
// KMK <--
