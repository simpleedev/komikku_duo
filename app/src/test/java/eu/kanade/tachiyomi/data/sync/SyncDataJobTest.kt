package eu.kanade.tachiyomi.data.sync

// KMK -->
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncDataJobTest {
    @Test
    fun `failed pre-library sync allows dependent update and retains failure indication`() {
        val request = SyncDataJob.beforeLibraryUpdate()
        assertTrue(request.tags.contains("SyncDataJob"))
        assertTrue(request.tags.contains(SyncDataJob.TAG_MANUAL))
        assertEquals("library-update", request.workSpec.input.getString("sync_trigger"))
        val result = SyncDataJob.failureResult(request.workSpec.input)
        val success = assertInstanceOf(ListenableWorker.Result.Success::class.java, result)
        assertTrue(success.outputData.getBoolean(SyncDataJob.KEY_SYNC_FAILED, false))
    }

    @Test
    fun `manual and automatic sync failures remain failed work`() {
        for (trigger in listOf("manual", "startup", "resume", "scheduled")) {
            assertInstanceOf(
                ListenableWorker.Result.Failure::class.java,
                SyncDataJob.failureResult(workDataOf("sync_trigger" to trigger)),
            )
        }
    }
}
// KMK <--
