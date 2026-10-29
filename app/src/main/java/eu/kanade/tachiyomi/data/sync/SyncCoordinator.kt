package eu.kanade.tachiyomi.data.sync

// KMK -->
import kotlinx.coroutines.sync.Mutex

/** Shared by scheduled/manual sync and file restores. Duplicate sync requests are coalesced. */
object SyncCoordinator {
    private val mutex = Mutex()

    suspend fun runSync(block: suspend () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        try {
            block()
        } finally {
            mutex.unlock()
        }
        return true
    }

    suspend fun <T> runRestore(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
// KMK <--
