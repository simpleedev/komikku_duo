package eu.kanade.tachiyomi.data.sync.service

// KMK -->
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class WebDavPreconditionException : IOException("WebDAV precondition failed")
class WebDavHttpException(val status: Int) : IOException("WebDAV HTTP $status")

class HttpWebDavTransport(
    private val client: OkHttpClient,
    private val url: String,
    private val authorization: String,
    private val protoBuf: ProtoBuf,
    private val createFolder: suspend () -> Unit = {},
) : WebDavTransport {
    override suspend fun read(etag: String?): WebDavRemote {
        val request = Request.Builder().url(url).header("Authorization", authorization)
            .header("Cache-Control", "no-cache")
            .apply { etag?.takeIf(WebDavSyncEngine::isStrongEtag)?.let { header("If-None-Match", it) } }
            .build()
        return client.newCall(request).await().use { response ->
            when (response.code) {
                304 -> {
                    if (etag == null) throw WebDavHttpException(304)
                    WebDavRemote.Unchanged
                }
                404 -> WebDavRemote.Missing
                200 -> WebDavRemote.Found(
                    // Decode failures are fatal. Never mistake a corrupt/incompatible file for absence.
                    protoBuf.decodeFromByteArray(Backup.serializer(), response.body.bytes()),
                    response.header("ETag"),
                )
                else -> throw WebDavHttpException(response.code)
            }
        }
    }

    override suspend fun write(backup: Backup, previous: WebDavRemote): String? {
        val etag = when (previous) {
            WebDavRemote.Missing -> null
            WebDavRemote.Unchanged -> error("An unchanged read must carry its cached ETag to the write")
            is WebDavRemote.Found -> previous.etag?.takeIf(WebDavSyncEngine::isStrongEtag)
                ?: throw WebDavUnsafeWriteException()
        }
        if (previous == WebDavRemote.Missing) createFolder()
        val bytes = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        val request = Request.Builder().url(url).header("Authorization", authorization)
            .apply {
                if (etag == null) header("If-None-Match", "*") else header("If-Match", etag)
            }
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return client.newCall(request).await().use { response ->
            when {
                response.code == 412 -> throw WebDavPreconditionException()
                !response.isSuccessful -> throw WebDavHttpException(response.code)
                // Do not HEAD here: another writer could have replaced our upload already.
                else -> response.header("ETag")?.takeIf(WebDavSyncEngine::isStrongEtag)
            }
        }
    }
}
// KMK <--
