package eu.kanade.tachiyomi.data.sync

// KMK -->
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Exercises the real coroutine handler and SQLDelight transacter, without Android SQLCipher. */
class WebDavDatabaseTransactionTest {
    @Test
    fun `awaited sync restore completes inside manager transaction and reports progress`() = runBlocking {
        withHandler { handler, driver ->
            val entries = listOf(BackupManga(1, "/one"), BackupManga(1, "/two"))
            val manga = mockk<MangaRestorer>()
            coEvery { manga.sortByNew(entries) } returns entries
            coEvery { manga.restore(any(), any()) } coAnswers {
                handler.await(inTransaction = true) {
                    // MangaRestorer and its repository operations each enter a transaction.
                    handler.await(inTransaction = true) { driver.rows.add(driver.rows.size + 1) }
                }
            }
            val restorer = BackupRestorer(
                context = mockk(),
                notifier = mockk(),
                isSync = true,
                categoriesRestorer = mockk(relaxed = true),
                preferenceRestorer = mockk(relaxed = true),
                extensionStoreRestorer = mockk(relaxed = true),
                mangaRestorer = manga,
                savedSearchRestorer = mockk(relaxed = true),
                feedRestorer = mockk(relaxed = true),
                handler = handler,
                syncMangaRestorerFactory = { manga },
            )
            val progress = mutableListOf<Float>()
            handler.await(inTransaction = true) {
                restorer.restoreSync(
                    Backup(entries),
                    RestoreOptions(),
                    onProgress = { progress.add(it) },
                    beforeRestore = { assertNotNull(driver.currentTransaction()) },
                )
                assertEquals(0, driver.commits)
                assertEquals(listOf(1, 2), driver.rows)
            }
            assertEquals(listOf(1f / 3, 2f / 3, 1f), progress)
            assertEquals(1, driver.commits)
        }
    }

    @Test
    fun `nested restore transactions and suspended queries share the outer transaction`() = runBlocking {
        withHandler { handler, driver ->
            handler.await(inTransaction = true) {
                val transactionThread = Thread.currentThread()
                val transactionId = checkNotNull(handler.suspendingTransactionId.get())
                handler.await(inTransaction = true) {
                    withContext(Dispatchers.Default) {
                        handler.await {
                            assertEquals(transactionThread, Thread.currentThread())
                            assertEquals(transactionId, checkNotNull(handler.suspendingTransactionId.get()))
                            assertNotNull(driver.currentTransaction())
                        }
                    }
                    handler.await(inTransaction = true) {
                        assertEquals(transactionThread, Thread.currentThread())
                        driver.rows.add(1)
                        handler.await(inTransaction = true) { driver.rows.add(2) }
                    }
                }
                assertEquals(0, driver.commits)
            }
            assertEquals(listOf(1, 2), driver.rows)
            assertEquals(1, driver.commits)
            assertEquals(0, driver.rollbacks)
        }
    }

    @Test
    fun `restore failure rolls back all nested changes and the next transaction succeeds`() = runBlocking {
        withHandler { handler, driver ->
            assertThrows(IOException::class.java) {
                runBlocking {
                    handler.await(inTransaction = true) {
                        driver.rows.add(1)
                        handler.await(inTransaction = true) {
                            driver.rows.add(2)
                            handler.await(inTransaction = true) { throw IOException("restore failed") }
                        }
                    }
                }
            }
            assertEquals(emptyList<Int>(), driver.rows)
            assertEquals(0, driver.commits)
            assertEquals(1, driver.rollbacks)
            handler.await(inTransaction = true) { driver.rows.add(3) }
            assertEquals(listOf(3), driver.rows)
            assertEquals(1, driver.commits)
        }
    }

    @Test
    fun `cancelling a suspended nested restore rolls back and releases its database thread`() = runBlocking {
        withHandler { handler, driver ->
            val restoring = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                handler.await(inTransaction = true) {
                    driver.rows.add(1)
                    handler.await(inTransaction = true) {
                        driver.rows.add(2)
                        restoring.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            withTimeout(5_000) { restoring.await() }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertEquals(emptyList<Int>(), driver.rows)
            assertEquals(0, driver.commits)
            assertEquals(1, driver.rollbacks)
            handler.await(inTransaction = true) { driver.rows.add(3) }
            assertEquals(listOf(3), driver.rows)
            assertEquals(1, driver.commits)
        }
    }

    private suspend fun withHandler(block: suspend (AndroidDatabaseHandler, TransactionDriver) -> Unit) {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val driver = TransactionDriver()
            val database = Database(driver, mockk(), mockk(), mockk())
            val handler = AndroidDatabaseHandler(database, driver, dispatcher, dispatcher)
            block(handler, driver)
        }
    }

    /** Models the driver's thread-bound, exclusive write transaction with rollback. */
    private class TransactionDriver : SqlDriver by mockk<SqlDriver>(relaxed = true) {
        private val transaction = ThreadLocal<Transacter.Transaction>()
        private val writeLock = ReentrantLock()
        val rows = mutableListOf<Int>()
        var commits = 0
        var rollbacks = 0

        override fun currentTransaction(): Transacter.Transaction? = transaction.get()

        override fun newTransaction(): QueryResult.Value<Transacter.Transaction> {
            val enclosing = transaction.get()
            if (enclosing == null) {
                check(writeLock.tryLock(2, TimeUnit.SECONDS)) { "Nested restore lost its transaction context" }
            }
            val snapshot = rows.toList()
            val next = object : Transacter.Transaction() {
                override val enclosingTransaction = enclosing

                override fun endTransaction(successful: Boolean): QueryResult.Value<Unit> {
                    if (!successful) {
                        rows.clear()
                        rows.addAll(snapshot)
                    }
                    transaction.set(enclosing)
                    if (enclosing == null) {
                        if (successful) commits++ else rollbacks++
                        writeLock.unlock()
                    }
                    return QueryResult.Value(Unit)
                }
            }
            transaction.set(next)
            return QueryResult.Value(next)
        }
    }
}
// KMK <--
