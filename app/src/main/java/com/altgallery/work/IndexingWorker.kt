package com.altgallery.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.altgallery.permissions.MediaPermissions
import com.altgallery.processing.IndexingPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/**
 * Runs one bounded indexing slice over the pending library photos.
 *
 * Behavior contract (INDX-004 + time-ceiling follow-up):
 * - No photo access → succeed quietly with no work (denied permission is a
 *   normal state, not an error; the home screen still loads its count).
 * - Each execution settles at most [MAX_PHOTOS_PER_EXECUTION] photos inside
 *   [RUN_TIME_BUDGET_MS] (see `RunSlice.kt`) as a foreground service with a
 *   progress notification, so a few-hundred-photo first run walks the library
 *   instead of holding one execution past WorkManager's ~10-minute cap. While
 *   unattempted photos from the pass remain, the worker appends a
 *   continuation ([IndexingScheduler.enqueueContinuation]) and succeeds; the
 *   tail skips URIs already logged by earlier slices, so one bad head cannot
 *   starve it. A stopped slice yields cooperatively via `isStopped` before
 *   the cap kills it; a hard kill falls back to the next cold start, which
 *   enqueues a fresh pass (completed photos are skipped via the
 *   already-processed definition, failed ones stay pending).
 * - A throw is mapped through [actionForRunThrow]: missing models succeed
 *   without retry (stable condition), transient throws retry under the
 *   scheduler's backoff until [MAX_RUN_ATTEMPTS] executions, then fail
 *   instead of looping forever — the next cold start enqueues a fresh run,
 *   so a healed backend recovers. A killed run is safe to repeat: completed
 *   photos are skipped via the already-processed definition, failed ones
 *   stay pending.
 * - Cancellation always propagates (including from the per-photo pause), so
 *   a replaced run stops promptly.
 *
 * Responsiveness: [CoroutineWorker] runs on a background dispatcher, the
 * repositories already confine I/O off the main thread, and the per-photo
 * callback yields — so a slice keeps the screen rendering and the home count
 * (a Room `Flow`) climbing without a freeze. Progress is published via
 * `setProgress` for future UI; the home count itself updates through the
 * database, not through this channel.
 *
 * Test scope: the throw→disposition table is pinned by plain-JVM tests (see
 * `RunDecisionTest`), the slice bounds by `RunSliceTest`; the worker body
 * only maps those tables onto `Result`. Enqueue behavior and the end-to-end
 * flows are verified on-device per the ticket's acceptance criteria.
 */
@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val indexingPipeline: IndexingPipeline,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(0, 1)

    override suspend fun doWork(): Result {
        if (!MediaPermissions.hasReadImages(applicationContext)) return Result.success()
        val isContinuation = inputData.getBoolean(CONTINUATION_KEY, false)
        return try {
            setForeground(createForegroundInfo(0, 1))
            val slice = indexingPipeline.runSlice(
                isContinuation = isContinuation,
                isStopped = { isStopped },
                clockMs = { SystemClock.elapsedRealtime() },
                onProgress = { done, total ->
                    setProgress(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
                    yield()
                },
            )
            if (slice.hasMore) {
                IndexingScheduler.enqueueContinuation(applicationContext)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (actionForRunThrow(e, runAttemptCount)) {
                RunAction.SUCCEED -> Result.success()
                RunAction.RETRY -> Result.retry()
                RunAction.FAIL -> {
                    Log.e(TAG, "Indexing run failed terminally; will resume on next cold start", e)
                    Result.failure()
                }
            }
        }
    }

    private fun createForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Library indexing",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
        val content = if (total > 1) "Indexing your library… $done of $total"
        else "Indexing your library…"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("AltGallery")
            .setContentText(content)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setOngoing(true)
            .setProgress(total, done, total <= 1)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        private const val TAG = "IndexingWorker"
        private const val CHANNEL_ID = "altgallery-indexing"
        private const val NOTIFICATION_ID = 41
    }
}
