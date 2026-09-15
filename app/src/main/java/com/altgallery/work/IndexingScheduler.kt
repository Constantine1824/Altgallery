package com.altgallery.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The single place indexing runs are requested from.
 *
 * Requested from two triggers, both unconditional:
 * - `AltGalleryApplication.onCreate` (every cold start: fresh install,
 *   reopen after a kill or force-stop);
 * - `MainActivity` right after photo access is granted.
 *
 * The work is unique ([UNIQUE_WORK_NAME]) with [ExistingWorkPolicy.REPLACE]:
 * at most one run exists at a time, and opening the app always converges to
 * a finished index. REPLACE is safe here because runs are idempotent —
 * cancelling a run in flight only abandons not-yet-written photos, which the
 * replacement run picks back up via the already-processed definition. A run
 * over an unchanged library finds nothing pending and succeeds immediately.
 * Retries after a transient throw back off exponentially from
 * [RETRY_BACKOFF_SECONDS] so a flapping run does not hot-loop, and give up
 * after [MAX_RUN_ATTEMPTS] executions (see `actionForRunThrow`) instead of
 * retrying forever — the next cold start then grants a fresh budget.
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
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
