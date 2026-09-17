package com.altgallery.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The single place indexing runs are requested from.
 *
 * Requested from two triggers, both unconditional:
 * - `AltGalleryApplication.onCreate` (every cold start: fresh install,
 *   reopen after a kill or force-stop);
 * - `MainActivity` right after photo access is granted.
 *
 * Both triggers fire on a normal cold start with permission already granted,
 * so the work is unique ([UNIQUE_WORK_NAME]) with [ExistingWorkPolicy.KEEP]:
 * at most one pass exists at a time, the second enqueue while the first is
 * still enqueued/running is a no-op, and opening the app no longer cancels
 * the in-flight run (REPLACE cost one photo's work per open). KEEP still
 * starts fresh work once the old chain finishes, so a grant after a no-op
 * denied run, or new photos after a finished pass, schedule normally.
 * Retries after a transient throw back off exponentially from
 * [RETRY_BACKOFF_SECONDS] so a flapping run does not hot-loop, and give up
 * after [MAX_RUN_ATTEMPTS] executions (see `actionForRunThrow`) instead of
 * retrying forever — the next cold start then grants a fresh budget.
 *
 * Long libraries run as a chain: each execution settles at most
 * [MAX_PHOTOS_PER_EXECUTION] photos inside [RUN_TIME_BUDGET_MS] (see
 * `RunSlice.kt`) and, while unattempted photos from the pass remain,
 * appends a continuation via [enqueueContinuation] ([ExistingWorkPolicy.APPEND]
 * runs it after the current execution succeeds). The continuation carries
 * [CONTINUATION_KEY] so the pipeline skips URIs already logged by earlier
 * slices instead of retrying the same head forever.
 *
 * Test scope: `WorkManager` needs a device, so the enqueue behavior is
 * verified on-device per the ticket's acceptance criteria.
 */
object IndexingScheduler {

    const val UNIQUE_WORK_NAME = "altgallery-indexing"

    const val RETRY_BACKOFF_SECONDS = 30L

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .setInputData(workDataOf(CONTINUATION_KEY to false))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Tail of the current library pass. Called by the worker after a slice
     * that leaves unattempted photos; APPEND runs it once the current
     * execution succeeds, so the pass walks the whole library without ever
     * holding one execution past the time ceiling.
     */
    fun enqueueContinuation(context: Context) {
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .setInputData(workDataOf(CONTINUATION_KEY to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND, request)
    }
}
