package eu.kanade.tachiyomi.data.sync

// KMK -->
import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.sync.service.WebDavConflictException
import eu.kanade.tachiyomi.data.sync.service.WebDavRemote
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncCheckpoint
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncEngine
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncMerger
import eu.kanade.tachiyomi.data.sync.service.WebDavTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

class WebDavSyncTest {
    private val codec = ProtoBuf
    private val merger = WebDavSyncMerger()

    private fun copy(value: Backup) = codec.decodeFromByteArray(Backup.serializer(), codec.encodeToByteArray(Backup.serializer(), value))

    private fun backup(): Backup = Backup(
        listOf(
            BackupManga(
                1,
                "/series",
                "Series",
                chapters = listOf(
                    BackupChapter("/one", "One"),
                    BackupChapter("/two", "Two"),
                ),
            ),
        ),
        backupPreferences = listOf(BackupPreference("theme", StringPreferenceValue("system"))),
        backupSourcePreferences = listOf(
            BackupSourcePreferences(
                "source",
                listOf(
                    BackupPreference("username", StringPreferenceValue("saved")),
                ),
            ),
        ),
    )

    private inner class Server(var data: Backup? = backup()) : WebDavTransport {
        var version = 1
        var downloads = 0
        var uploads = 0
        var reliable = true
        var error: IOException? = null
        override suspend fun read(etag: String?): WebDavRemote {
            error?.let { throw it }
            if (reliable && etag == "\"$version\"") return WebDavRemote.Unchanged
            val backup = data ?: return WebDavRemote.Missing
            downloads++
            return WebDavRemote.Found(copy(backup), if (reliable) "\"$version\"" else null)
        }
        override suspend fun write(backup: Backup, previous: WebDavRemote): String? {
            uploads++
            data = copy(backup)
            version++
            return if (reliable) "\"$version\"" else null
        }
    }

    private inner class Device(val server: Server, var data: Backup = backup()) {
        var checkpoint: WebDavSyncCheckpoint? = null
        var restores = 0
        var recoveries = 0
        var failRestore: Exception? = null
        suspend fun sync() {
            WebDavSyncEngine(server).sync(data, checkpoint, restore = {
                restores++
                failRestore?.let { failure -> throw failure }
                data = copy(it)
                data
            }, protect = { _, _ -> recoveries++ }, commit = {
                // Round-trip the persisted checkpoint; a process restart must behave identically.
                checkpoint = Json.decodeFromString(
                    WebDavSyncCheckpoint.serializer(),
                    Json.encodeToString(WebDavSyncCheckpoint.serializer(), it),
                )
            })
        }
        fun read(index: Int) {
            data.backupManga.single().chapters[index].apply {
                read = true
                version++
            }
            data.backupManga.single().version++
        }
    }

    @Test
    fun `identical legacy remote needs no restore or upload`() = runTest {
        val server = Server()
        val device = Device(server)
        device.sync()
        assertEquals(0, device.restores)
        assertEquals(0, server.uploads)
        assertEquals(1, server.downloads)
        assertNotEquals(null, device.checkpoint)
    }

    @Test
    fun `five launches while other device is offline perform no restore or download`() = runTest {
        val server = Server()
        val first = Device(server)
        first.sync()
        repeat(5) {
            val reopened = Device(server, copy(first.data)).apply { checkpoint = first.checkpoint }
            reopened.sync()
            assertEquals(0, reopened.restores)
        }
        assertEquals(1, server.downloads)
        assertEquals(0, server.uploads)
    }

    @Test
    fun `local progress uploads without restoring`() = runTest {
        val server = Server()
        val device = Device(server)
        device.sync()
        device.read(0)
        device.sync()
        assertEquals(1, server.uploads)
        assertEquals(0, device.restores)
        assertTrue(server.data!!.backupManga.single().chapters[0].read)
    }

    @Test
    fun `remote progress is applied without a redundant upload`() = runTest {
        val server = Server()
        val device = Device(server)
        device.sync()
        server.data!!.backupManga.single().chapters[0].apply {
            read = true
            version++
        }
        server.data!!.backupManga.single().version++
        server.version++
        device.sync()
        assertEquals(1, device.restores)
        assertEquals(0, server.uploads)
        assertTrue(device.data.backupManga.single().chapters[0].read)
    }

    @Test
    fun `offline reconnect merges independent equal version edits and converges`() = runTest {
        val server = Server()
        val a = Device(server)
        val b = Device(server)
        a.sync()
        b.sync()
        a.read(0)
        a.sync()
        b.read(1)
        b.sync()
        a.sync()
        assertTrue(a.data.backupManga.single().chapters.all { it.read })
        assertTrue(b.data.backupManga.single().chapters.all { it.read })
        assertEquals(merger.revision(a.data), merger.revision(b.data))
        val restores = a.restores + b.restores
        a.sync()
        b.sync()
        assertEquals(restores, a.restores + b.restores)
    }

    @Test
    fun `read and bookmark changes to the same chapter merge independently`() {
        val base = backup()
        val local = copy(base).apply { backupManga[0].chapters[0].read = true }
        val remote = copy(base).apply { backupManga[0].chapters[0].bookmark = true }
        val merged = merger.merge(local, remote, WebDavSyncCheckpoint(base, base, "\"1\""))
        assertTrue(merged.backupManga[0].chapters[0].read)
        assertTrue(merged.backupManga[0].chapters[0].bookmark)
    }

    @Test
    fun `same field conflict leaves local remote and checkpoint intact`() = runTest {
        val server = Server()
        val a = Device(server)
        a.sync()
        val previous = a.checkpoint
        a.data.backupManga[0].chapters[0].lastPageRead = 4
        server.data!!.backupManga[0].chapters[0].lastPageRead = 8
        server.version++
        try {
            a.sync()
            error("Expected conflict")
        } catch (_: WebDavConflictException) {
            assertEquals(previous, a.checkpoint)
            assertEquals(4L, a.data.backupManga[0].chapters[0].lastPageRead)
            assertEquals(8L, server.data!!.backupManga[0].chapters[0].lastPageRead)
            assertEquals(0, a.restores)
            assertEquals(0, server.uploads)
            assertTrue(a.recoveries > 0)
        }
    }

    @Test
    fun `unavailable server and timeout do not mutate local or acknowledge changes`() = runTest {
        for (failure in listOf(IOException("offline"), SocketTimeoutException())) {
            val server = Server().apply { error = failure }
            val device = Device(server)
            val revision = merger.revision(device.data)
            try {
                device.sync()
                error("Expected network failure")
            } catch (_: IOException) {
                assertEquals(revision, merger.revision(device.data))
                assertNull(device.checkpoint)
                assertEquals(0, device.restores)
                assertEquals(0, server.uploads)
            }
        }
    }

    @Test
    fun `missing metadata downloads and compares contents without restoring`() = runTest {
        val server = Server().apply { reliable = false }
        val device = Device(server)
        repeat(6) { device.sync() }
        assertEquals(6, server.downloads)
        assertEquals(0, server.uploads)
        assertEquals(0, device.restores)
    }

    @Test
    fun `failed and cancelled restore never commit and retry can recover`() = runTest {
        for (failure in listOf(IOException("restore failed"), CancellationException("interrupted"))) {
            val server = Server()
            val device = Device(server)
            device.sync()
            val previous = device.checkpoint
            server.data!!.backupManga[0].chapters[0].read = true
            server.version++
            device.failRestore = failure
            try {
                device.sync()
                error("Expected restore failure")
            } catch (_: Exception) {
                assertEquals(previous, device.checkpoint)
                assertFalse(device.data.backupManga[0].chapters[0].read)
            }
            device.failRestore = null
            device.sync()
            assertTrue(device.data.backupManga[0].chapters[0].read)
        }
    }

    @Test
    fun `no-op preserves application and source settings`() = runTest {
        val server = Server()
        val device = Device(server)
        val preferences = device.data.backupPreferences
        val sourcePreferences = device.data.backupSourcePreferences
        repeat(6) { device.sync() }
        assertEquals(preferences, device.data.backupPreferences)
        assertEquals(sourcePreferences, device.data.backupSourcePreferences)
        assertEquals(0, device.restores)
    }

    @Test
    fun `local source preference edit is uploaded rather than reverted to remote`() = runTest {
        val server = Server()
        val device = Device(server)
        device.sync()
        device.data.backupSourcePreferences = listOf(
            BackupSourcePreferences(
                "source",
                listOf(
                    BackupPreference("username", StringPreferenceValue("edited")),
                ),
            ),
        )
        device.sync()
        assertEquals(device.data.backupSourcePreferences, server.data!!.backupSourcePreferences)
        assertEquals(0, device.restores)
    }

    @Test
    fun `legacy backup bootstrap preserves local settings and does not drop duplicate keys`() {
        val local = backup()
        val remote = backup().apply {
            backupPreferences = listOf(BackupPreference("theme", StringPreferenceValue("old")))
        }
        val merged = merger.merge(local, remote, null)
        assertEquals(local.backupPreferences, merged.backupPreferences)
        assertEquals(local.backupSourcePreferences, merged.backupSourcePreferences)
        val roundTrip = copy(merged)
        assertEquals(merger.revision(merged), merger.revision(roundTrip))
    }

    @Test
    fun `missing remote is created without restoring local data`() = runTest {
        val server = Server(null)
        val device = Device(server)
        device.sync()
        assertEquals(1, server.uploads)
        assertEquals(0, device.restores)
    }

    @Test
    fun `empty library still synchronizes settings`() = runTest {
        val server = Server(Backup(emptyList()))
        val device = Device(server, Backup(emptyList(), backupPreferences = backup().backupPreferences))
        device.sync()
        assertEquals(device.data.backupPreferences, server.data!!.backupPreferences)
        assertEquals(0, device.restores)
    }

    @Test
    fun `missing records are retained rather than treated as offline deletions`() {
        val local = Backup(emptyList())
        val remote = backup()
        assertEquals(1, merger.merge(local, remote, null).backupManga.size)
    }

    @Test
    fun `category order collisions retain memberships by name`() {
        val local = backup().apply {
            backupCategories = listOf(BackupCategory("Local", 0, 1))
            backupManga[0].categories = listOf(0)
        }
        val remote = backup().apply {
            backupCategories = listOf(BackupCategory("Remote", 0, 1))
            backupManga[0].url = "/another"
            backupManga[0].categories = listOf(0)
        }
        val merged = merger.merge(local, remote, null)
        assertEquals(2, merged.backupCategories.map { it.order }.distinct().size)
        val names = merged.backupCategories.associate { it.order to it.name }
        assertEquals("Local", names[merged.backupManga[0].categories.single()])
        assertEquals("Remote", names[merged.backupManga[1].categories.single()])
    }

    @Test
    fun `history from each device is retained`() {
        val local = backup().apply { backupManga[0].history = listOf(BackupHistory("/one", 100, 5)) }
        val remote = backup().apply { backupManga[0].history = listOf(BackupHistory("/two", 200, 10)) }
        assertEquals(2, merger.merge(local, remote, null).backupManga[0].history.size)
    }

    @Test
    fun `category preferences follow names when device IDs collide`() {
        val local = backup().apply {
            backupCategories = listOf(BackupCategory("Local", 0, 9))
            backupPreferences = emptyList()
        }
        val remote = backup().apply {
            backupCategories = listOf(BackupCategory("Remote", 0, 9))
            backupPreferences = listOf(BackupPreference("default_category", IntPreferenceValue(9)))
        }
        val merged = merger.merge(local, remote, null)
        val id = (merged.backupPreferences.single().value as IntPreferenceValue).value.toLong()
        assertEquals("Remote", merged.backupCategories.single { it.id == id }.name)
    }

    @Test
    fun `equivalent category IDs do not force a restore`() {
        val local = backup().apply {
            backupCategories = listOf(BackupCategory("Reading", 0, 5))
            backupManga[0].categories = listOf(0)
            backupPreferences = listOf(BackupPreference("default_category", IntPreferenceValue(5)))
        }
        val remote = copy(local).apply {
            backupCategories[0].id = 12
            backupPreferences = listOf(BackupPreference("default_category", IntPreferenceValue(12)))
        }
        assertEquals(merger.revision(local), merger.revision(remote))
    }

    @Test
    fun `disabled private settings are retained remotely but cannot be restored`() {
        val local = backup()
        val remote = backup().apply {
            backupPreferences = backupPreferences + BackupPreference("__PRIVATE_secret", StringPreferenceValue("retained"))
        }
        val merged = merger.merge(local, remote, null)
        assertEquals(2, merged.backupPreferences.size)
        assertEquals(1, merger.forRestore(merged).backupPreferences.size)
    }

    @Test
    fun `independent offline application preference edits retain both values`() {
        val base = backup().apply {
            backupPreferences = backupPreferences + BackupPreference("layout", StringPreferenceValue("list"))
        }
        val local = copy(base).apply {
            backupPreferences = backupPreferences.map {
                if (it.key == "theme") it.copy(value = StringPreferenceValue("dark")) else it
            }
        }
        val remote = copy(base).apply {
            backupPreferences = backupPreferences.map {
                if (it.key == "layout") it.copy(value = StringPreferenceValue("grid")) else it
            }
        }
        val prefs = merger.merge(local, remote, WebDavSyncCheckpoint(base, base, null)).backupPreferences.associateBy { it.key }
        assertEquals(StringPreferenceValue("dark"), prefs.getValue("theme").value)
        assertEquals(StringPreferenceValue("grid"), prefs.getValue("layout").value)
    }

    @Test
    fun `disabled sections and connection settings are never restored`() {
        val value = backup().apply {
            backupPreferences = backupPreferences + BackupPreference("connection_webdav_password", StringPreferenceValue("secret"))
        }
        val scoped = WebDavSyncMerger(SyncSettings(sourceSettings = false, chapters = false)).forRestore(value)
        assertTrue(scoped.backupSourcePreferences.isEmpty())
        assertTrue(scoped.backupManga[0].chapters.isEmpty())
        assertEquals(1, scoped.backupPreferences.size)
    }

    @Test
    fun `timestamps and collection order do not define meaningful changes`() {
        val first = backup()
        val second = copy(first).apply {
            backupManga[0].lastModifiedAt = 500
            backupManga[0].chapters[0].lastModifiedAt = 500
            backupManga[0].chapters = backupManga[0].chapters.reversed()
        }
        assertEquals(merger.revision(first), merger.revision(second))
        second.backupManga[0].chapters[0].bookmark = true
        assertNotEquals(merger.revision(first), merger.revision(second))
    }

    @Test
    fun `only quoted strong entity tags are reliable`() {
        assertTrue(WebDavSyncEngine.isStrongEtag("\"123\""))
        listOf("", "123", "W/\"123\"", "\"bad\nvalue\"", "\"a\", \"b\"", "\"bad value\"").forEach {
            assertFalse(WebDavSyncEngine.isStrongEtag(it))
        }
    }

    @Test
    fun `concurrent requests coalesce and a failure releases the coordinator`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async {
            SyncCoordinator.runSync {
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        val duplicates = coroutineScope { List(20) { async { SyncCoordinator.runSync { error("Overlapping sync") } } }.awaitAll() }
        assertTrue(duplicates.none { it })
        finish.complete(Unit)
        assertTrue(first.await())
        try {
            SyncCoordinator.runSync { throw IOException() }
        } catch (_: IOException) {
            assertTrue(SyncCoordinator.runSync {})
        }
    }
}
// KMK <--
