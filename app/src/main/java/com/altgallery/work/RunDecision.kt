package com.altgallery.work

import com.altgallery.processing.ModelsMissingException

/** Terminal disposition of one worker attempt that threw. */
internal enum class RunAction {
    /** Settle the run as done: nothing to retry. */
    SUCCEED,

    /** Try the whole run again under the scheduler's backoff. */
    RETRY,

    /** Stop: the run-level budget is spent, do not loop forever. */
    FAIL,
}

/**
 * Run-level retry budget for transient throws (the per-photo budget lives in
 * `BatchRunner`). Five executions total, spaced by the scheduler's
 * exponential backoff.
 */
internal const val MAX_RUN_ATTEMPTS = 5

/**
 * Pure decision table behind `IndexingWorker.doWork`, pinned by plain-JVM
 * tests (see `RunDecisionTest`); the worker only maps the outcome onto
 * `Result`:
 * - missing models always succeed: the condition is stable, so retrying
 *   would loop forever on the same error;
 * - anything else retries until the run-level budget is spent, then fails.
 *   A failed run does not trap the library: the next cold start enqueues a
 *   fresh run with a fresh budget, so a healed backend (database, storage)
 *   recovers on its own, while the backoff keeps a broken one from
 *   hot-looping.
 *
 * @param runAttemptCount WorkManager's 0-based attempt index for this run.
 */
internal fun actionForRunThrow(error: Exception, runAttemptCount: Int): RunAction = when {
    error is ModelsMissingException -> RunAction.SUCCEED
    runAttemptCount >= MAX_RUN_ATTEMPTS - 1 -> RunAction.FAIL
    else -> RunAction.RETRY
}
