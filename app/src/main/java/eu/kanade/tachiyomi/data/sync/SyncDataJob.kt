package eu.kanade.tachiyomi.data.sync

// KMK -->
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncFailure
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.log.xLogD
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit
// KMK <--

class SyncDataJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = SyncNotifier(context)

    override suspend fun doWork(): Result {
        // KMK -->
        xLogD("Sync worker trigger=%s", inputData.getString("sync_trigger") ?: "scheduled/library-update")
        // KMK <--
        if (tags.contains(TAG_AUTO)) {
            if (!context.isOnline()) {
                return Result.retry()
            }
            // Find a running manual worker. If exists, try again later
            if (context.workManager.isRunning(TAG_MANUAL)) {
                // KMK -->
                return Result.success() // Coalesce scheduled work with the manual sync already in progress.
                // KMK <--
            }
        }

        setForegroundSafely()

        return try {
            SyncManager(context).syncData()
            Result.success()
        } catch (e: Exception) {
            // KMK -->
            if (e is CancellationException) throw e
            if (e is WebDavSyncFailure) return failureResult(inputData)
            if (Injekt.get<SyncPreferences>().syncService().get() == SyncManager.SyncService.WEB_DAV.value) {
                xLogD("WebDAV failed type=%s", e.javaClass.simpleName)
                notifier.showSyncError(context.stringResource(KMR.strings.webdav_sync_failed))
                return failureResult(inputData)
            }
            // KMK <--
            logcat(LogPriority.ERROR, e)
            notifier.showSyncError(e.message)
            Result.success() // try again next time
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_SYNC_PROGRESS,
            notifier.showSyncProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG_JOB = "SyncDataJob"
        private const val TAG_AUTO = "$TAG_JOB:auto"
        const val TAG_MANUAL = "$TAG_JOB:manual"

        // KMK -->
        private const val KEY_CONTINUE_LIBRARY_UPDATE = "continue_library_update"
        internal const val KEY_SYNC_FAILED = "sync_failed"

        fun beforeLibraryUpdate(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncDataJob>()
            .addTag(TAG_JOB)
            .addTag(TAG_MANUAL)
            .setInputData(
                workDataOf("sync_trigger" to "library-update", KEY_CONTINUE_LIBRARY_UPDATE to true),
            )
            .build()

        internal fun failureResult(input: Data): Result {
            // A WebDAV error is already reported to the user. Let the dependent library worker
            // refresh local entries after the failed sync has unwound and released its transaction.
            // Manual/scheduled syncs still finish as failed; cancellation is never converted here.
            return if (input.getBoolean(KEY_CONTINUE_LIBRARY_UPDATE, false)) {
                Result.success(workDataOf(KEY_SYNC_FAILED to true))
            } else {
                Result.failure()
            }
        }
        // KMK <--

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG_JOB)
        }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val syncPreferences = Injekt.get<SyncPreferences>()
            val interval = prefInterval ?: syncPreferences.syncInterval().get()

            if (interval > 0) {
                val request = PeriodicWorkRequestBuilder<SyncDataJob>(
                    interval.toLong(),
                    TimeUnit.MINUTES,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG_JOB)
                    .addTag(TAG_AUTO)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(TAG_AUTO, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                context.workManager.cancelUniqueWork(TAG_AUTO)
            }
        }

        // KMK -->
        fun startNow(context: Context, manual: Boolean = false, trigger: String = if (manual) "manual" else "automatic") {
            // KMK <--
            val wm = context.workManager
            if (wm.isRunning(TAG_JOB)) {
                // Already running either as a scheduled or manual job
                return
            }
            // KMK -->
            // One unique immediate-work name for manual, startup, resume and chapter triggers.
            // The periodic worker retains its own schedule; the shared coordinator covers both.
            val tag = if (manual) TAG_MANUAL else TAG_AUTO
            xLogD("Sync requested trigger=%s", trigger)
            // KMK <--
            val request = OneTimeWorkRequestBuilder<SyncDataJob>()
                // KMK -->
                .setInputData(workDataOf("sync_trigger" to trigger))
                // KMK <--
                .addTag(TAG_JOB)
                .addTag(tag)
                .build()
            // KMK -->
            context.workManager.enqueueUniqueWork(TAG_JOB, ExistingWorkPolicy.KEEP, request)
            // KMK <--
        }

        fun stop(context: Context) {
            // KMK -->
            val syncPreferences = Injekt.get<SyncPreferences>()
            val syncEnabled = syncPreferences.isSyncEnabled()
            // KMK <--
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG_JOB, TAG_AUTO, TAG_MANUAL))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                // Should only return one work but just in case
                .forEach {
                    wm.cancelWorkById(it.id)

                    // Re-enqueue cancelled scheduled work
                    if (/* KMK --> */ syncEnabled /* KMK <-- */ && it.tags.contains(TAG_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
