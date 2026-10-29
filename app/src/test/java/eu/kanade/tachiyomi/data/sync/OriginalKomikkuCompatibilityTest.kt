package eu.kanade.tachiyomi.data.sync

// KMK -->
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.data.sync.service.SyncService
import eu.kanade.tachiyomi.data.sync.service.WebDavConflictException
import eu.kanade.tachiyomi.data.sync.service.WebDavRemote
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncCheckpoint
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncEngine
import eu.kanade.tachiyomi.data.sync.service.WebDavTransport
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercise the unmodified upstream-style merger still used by other sync providers in this fork.
 * This characterizes mixed backup exchanges, not Android restoration on an original release APK.
 */
class OriginalKomikkuCompatibilityTest {
    private fun copy(backup: Backup): Backup = ProtoBuf.decodeFromByteArray(
        Backup.serializer(),
        ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
    )

    private fun initial(): Backup = Backup(
        listOf(
            BackupManga(
                1,
                "/series",
                "Series",
                chapters = listOf(
                    BackupChapter("/one", "One"),
                    BackupChapter("/two", "Two"),
                    BackupChapter("/three", "Three"),
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

    private class OriginalMerger : SyncService(
        mockk(),
        Json,
        mockk<SyncPreferences> { every { uniqueDeviceID() } returns "original-komikku" },
    ) {
        override suspend fun doSync(syncData: SyncData): Backup? = error("Only the original merge is under test")
        fun merge(local: Backup, remote: Backup): Backup =
            checkNotNull(mergeSyncData(SyncData(backup = local), SyncData(backup = remote)).backup)
    }

    private val original = OriginalMerger()

    private inner class SharedFile(var data: Backup = initial()) : WebDavTransport {
        var generation = 1
        var uploads = 0
        var downloads = 0
        fun originalSync(local: Backup): Backup {
            data = copy(original.merge(copy(local), copy(data)))
            generation++
            return copy(data)
        }
        override suspend fun read(etag: String?): WebDavRemote {
            if (etag == "\"$generation\"") return WebDavRemote.Unchanged
            downloads++
            return WebDavRemote.Found(copy(data), "\"$generation\"")
        }
        override suspend fun write(backup: Backup, previous: WebDavRemote): String {
            data = copy(backup)
            generation++
            uploads++
            return "\"$generation\""
        }
    }

    private inner class Duo(val file: SharedFile) {
        var local = initial()
        var checkpoint: WebDavSyncCheckpoint? = null
        var restores = 0
        var recoveries = 0
        suspend fun sync() {
            WebDavSyncEngine(file).sync(local, checkpoint, restore = {
                restores++
                local = copy(it)
                local
            }, protect = { _, _ -> recoveries++ }, commit = {
                // Production persists these snapshots; later local edits cannot mutate the baseline.
                checkpoint = it.copy(local = copy(it.local), remote = copy(it.remote))
            })
        }
    }

    private fun read(backup: Backup, index: Int) {
        backup.backupManga[0].chapters[index].apply {
            read = true
            version++
        }
        backup.backupManga[0].version++
    }

    @Test
    fun `five Duo opens need no restore while both original devices stay offline`() = runTest {
        val file = SharedFile()
        file.originalSync(initial())
        val duo = Duo(file)
        duo.sync()
        val uploads = file.uploads
        val downloads = file.downloads
        repeat(5) { duo.sync() }
        assertEquals(0, duo.restores)
        assertEquals(uploads, file.uploads)
        assertEquals(downloads, file.downloads)
    }

    @Test
    fun `Duo and two original mergers retain independent chapter reads`() = runTest {
        val file = SharedFile()
        val duo = Duo(file)
        val phone = initial()
        val tablet = initial()
        duo.sync()
        read(duo.local, 0)
        duo.sync()
        read(phone, 1)
        val phoneResult = file.originalSync(phone)
        assertTrue(phoneResult.backupManga[0].chapters.take(2).all { it.read })
        duo.sync()
        read(tablet, 2)
        val tabletResult = file.originalSync(tablet)
        assertTrue(tabletResult.backupManga[0].chapters.all { it.read })
        duo.sync()
        assertTrue(duo.local.backupManga[0].chapters.all { it.read })
        val phoneAfterAnotherSync = file.originalSync(phoneResult)
        assertTrue(phoneAfterAnotherSync.backupManga[0].chapters.all { it.read })
    }

    @Test
    fun `original preference stripping does not force a Duo restore or revert Duo settings`() = runTest {
        val file = SharedFile()
        val duo = Duo(file)
        duo.sync()
        repeat(3) {
            file.originalSync(initial())
            assertTrue(file.data.backupPreferences.isEmpty()) // Original bug, not a deletion instruction.
            duo.sync()
            assertEquals(initial().backupPreferences, duo.local.backupPreferences)
            assertEquals(0, duo.restores)
        }
    }

    @Test
    fun `original source preference edit can be lost before Duo observes it`() {
        val phone = initial().apply {
            backupSourcePreferences = listOf(
                BackupSourcePreferences(
                    "source",
                    listOf(
                        BackupPreference("username", StringPreferenceValue("phone-edit")),
                    ),
                ),
            )
        }
        val result = original.merge(phone, initial())
        assertEquals(StringPreferenceValue("saved"), result.backupSourcePreferences[0].prefs[0].value)
        // This limitation cannot be repaired by code installed only on the Duo.
    }

    @Test
    fun `original equal-version chapter selection can discard a different devices read`() {
        val duo = initial().apply { read(this, 0) }
        val phone = initial().apply {
            backupManga[0].chapters[0].apply {
                bookmark = true
                version++
            }
            backupManga[0].version++
        }
        val result = original.merge(phone, duo)
        assertFalse(result.backupManga[0].chapters[0].read)
        assertTrue(result.backupManga[0].chapters[0].bookmark)
    }

    @Test
    fun `Duo refuses an original equal-version rewrite that would erase its acknowledged read`() = runTest {
        val file = SharedFile()
        val duo = Duo(file)
        duo.sync()
        read(duo.local, 0)
        duo.sync()
        val phone = initial().apply {
            backupManga[0].chapters[0].apply {
                bookmark = true
                version++
            }
            backupManga[0].version++
        }
        file.originalSync(phone)
        val checkpoint = duo.checkpoint
        try {
            duo.sync()
            error("Expected ambiguous original-client regression to be preserved as a conflict")
        } catch (_: WebDavConflictException) {
            assertTrue(duo.local.backupManga[0].chapters[0].read)
            assertEquals(checkpoint, duo.checkpoint)
            assertEquals(0, duo.restores)
            assertTrue(duo.recoveries > 0)
        }
    }

    @Test
    fun `intentional unread with an advancing original chapter version remains supported`() = runTest {
        val file = SharedFile()
        val duo = Duo(file)
        duo.sync()
        read(duo.local, 0)
        duo.sync()
        val phone = copy(file.data).apply {
            backupManga[0].chapters[0].apply {
                read = false
                version++
            }
            backupManga[0].version++
        }
        file.originalSync(phone)
        duo.sync()
        assertFalse(duo.local.backupManga[0].chapters[0].read)
        assertEquals(1, duo.restores)
    }
}
// KMK <--
