package eu.kanade.tachiyomi.data.sync

// KMK -->
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.service.HttpWebDavTransport
import eu.kanade.tachiyomi.data.sync.service.WebDavHttpException
import eu.kanade.tachiyomi.data.sync.service.WebDavPreconditionException
import eu.kanade.tachiyomi.data.sync.service.WebDavRemote
import eu.kanade.tachiyomi.data.sync.service.WebDavUnsafeWriteException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real HTTP against an in-process WebDAV stand-in, without adding a test dependency. */
class WebDavTransportTest {
    private val executor = Executors.newCachedThreadPool()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
    private val backup = Backup(emptyList())
    private val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
    private val methods = CopyOnWriteArrayList<String>()
    private val headers = CopyOnWriteArrayList<Map<String, String>>()

    private fun transport(
        code: Int,
        tag: String? = "\"quoted\"",
        content: ByteArray = bytes,
        delayMillis: Long = 0,
        timeoutMillis: Long = 2000,
    ): HttpWebDavTransport {
        executor.submit {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: java.io.IOException) {
                    break
                }
                socket.use {
                    val input = it.getInputStream().buffered()
                    fun line(): String = buildString {
                        while (true) {
                            val byte = input.read()
                            if (byte < 0 || byte == 10) break
                            if (byte != 13) append(byte.toChar())
                        }
                    }
                    methods += line().substringBefore(' ')
                    val requestHeaders = mutableMapOf<String, String>()
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        requestHeaders[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    headers += requestHeaders
                    input.readNBytes(requestHeaders["content-length"]?.toInt() ?: 0)
                    if (delayMillis > 0) Thread.sleep(delayMillis)
                    val responseCode = if (requestHeaders["if-none-match"] == "\"quoted\"" && code == 200) 304 else code
                    val body = if (responseCode == 304) byteArrayOf() else content
                    val responseHeaders = "HTTP/1.1 $responseCode Test\r\nConnection: close\r\nContent-Length: ${body.size}\r\n" +
                        (tag?.let { "ETag: $it\r\n" } ?: "") + "\r\n"
                    it.getOutputStream().apply {
                        write(responseHeaders.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        return HttpWebDavTransport(
            client.newBuilder().readTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build(),
            "http://127.0.0.1:${server.localPort}/komikku/backup.proto",
            "Basic test",
            ProtoBuf,
        )
    }

    @AfterEach
    fun close() {
        server.close()
        executor.shutdownNow()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    @Test
    fun `conditional GET retains exact quoted ETag and avoids body download`() = runBlocking {
        val transport = transport(200)
        val found = transport.read(null) as WebDavRemote.Found
        assertEquals("\"quoted\"", found.etag)
        assertEquals(WebDavRemote.Unchanged, transport.read(found.etag))
        assertFalse(methods.contains("PUT"))
    }

    @Test
    fun `conditional PUT uses quoted If-Match`() = runBlocking {
        val transport = transport(200)
        assertEquals("\"quoted\"", transport.write(backup, WebDavRemote.Found(backup, "\"quoted\"")))
        assertEquals(listOf("PUT"), methods.toList())
        assertEquals("\"quoted\"", headers.single()["if-match"])
    }

    @Test
    fun `new backup uses If-None-Match star`() = runBlocking {
        transport(201).write(backup, WebDavRemote.Missing)
        assertEquals(listOf("PUT"), methods.toList())
        assertEquals("*", headers.single()["if-none-match"])
    }

    @Test
    fun `precondition failure cannot be reported as upload success`() {
        val transport = transport(412)
        assertThrows(WebDavPreconditionException::class.java) {
            runBlocking { transport.write(backup, WebDavRemote.Found(backup, "\"quoted\"")) }
        }
    }

    @Test
    fun `no ETag weak ETag and malformed ETag never permit an unsafe overwrite`() {
        val transport = transport(200, null)
        for (tag in listOf(null, "W/\"weak\"", "unquoted")) {
            assertThrows(WebDavUnsafeWriteException::class.java) {
                runBlocking { transport.write(backup, WebDavRemote.Found(backup, tag)) }
            }
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `legacy protobuf body without sync metadata can be decoded`() = runBlocking {
        val found = transport(200, null).read(null) as WebDavRemote.Found
        assertTrue(found.backup.backupManga.isEmpty())
        assertEquals(null, found.etag)
    }

    @Test
    fun `missing backup and not modified have distinct results`() = runBlocking {
        assertEquals(WebDavRemote.Missing, transport(404).read(null))
    }

    @Test
    fun `invalid remote data fails without an upload`() {
        val transport = transport(200, content = byteArrayOf(0, -1, 0))
        assertThrows(Exception::class.java) { runBlocking { transport.read(null) } }
        assertFalse(methods.contains("PUT"))
    }

    @Test
    fun `authentication and server failures never become missing backups`() {
        val transport = transport(503)
        assertThrows(WebDavHttpException::class.java) { runBlocking { transport.read(null) } }
        assertFalse(methods.contains("PUT"))
    }

    @Test
    fun `request timeout propagates and never uploads`() {
        val transport = transport(200, delayMillis = 1000, timeoutMillis = 100)
        val failure = assertThrows(IOException::class.java) { runBlocking { transport.read(null) } }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is SocketTimeoutException })
        assertEquals(listOf("GET"), methods.toList())
    }
}
// KMK <--
