package eu.kanade.tachiyomi.data.sync

// KMK -->
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import java.io.IOException

class WebDavRestoreTest {
    @Test
    fun `restore propagates failure within transaction and does not start preferences`() = runTest {
        for (failure in listOf(IOException("restore failed"), CancellationException("cancelled"))) {
            val handler = mockk<DatabaseHandler>()
            val database = mockk<Database>()
            val manga = mockk<MangaRestorer>()
            val preferences = mockk<PreferenceRestorer>(relaxed = true)
            val entries = listOf(BackupManga(1, "/one"), BackupManga(1, "/two"))
            var transactionCommitted = false
            var transactionRolledBack = false
            var insideTransaction = false
            coEvery { handler.await<Unit>(true, any()) } coAnswers {
                insideTransaction = true
                try {
                    secondArg<suspend Database.() -> Unit>().invoke(database)
                    transactionCommitted = true
                } catch (e: Exception) {
                    transactionRolledBack = true
                    throw e
                } finally {
                    insideTransaction = false
                }
            }
            coEvery { manga.sortByNew(entries) } returns entries
            coEvery { manga.restore(entries[0], any()) } coAnswers { assertTrue(insideTransaction) }
            coEvery { manga.restore(entries[1], any()) } throws failure
            val restorer = BackupRestorer(
                context = mockk(),
                notifier = mockk(),
                isSync = true,
                categoriesRestorer = mockk(relaxed = true),
                preferenceRestorer = preferences,
                extensionStoreRestorer = mockk(relaxed = true),
                mangaRestorer = manga,
                savedSearchRestorer = mockk(relaxed = true),
                feedRestorer = mockk(relaxed = true),
                handler = handler,
                syncMangaRestorerFactory = { manga },
            )
            try {
                restorer.restoreSync(Backup(entries), RestoreOptions()) { assertTrue(insideTransaction) }
                error("Expected restore failure")
            } catch (e: Exception) {
                assertTrue(e === failure)
            }
            assertFalse(transactionCommitted)
            assertTrue(transactionRolledBack)
            coVerify(exactly = 0) { preferences.restoreApp(any(), any(), any()) }
            coVerify(exactly = 0) { preferences.restoreSource(any(), any()) }
        }
    }
}
// KMK <--
