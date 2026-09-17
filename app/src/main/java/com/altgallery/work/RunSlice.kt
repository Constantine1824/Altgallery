package com.altgallery.work

/**
 * Execution-time ceiling for indexing runs (INDX-004 follow-up).
 *
 * WorkManager stops a worker execution at ~10 minutes with nothing resuming
 * it: the previous shape ran the entire pending set in one execution with no
 * foreground service, so a few-hundred-photo first run could be killed
 * mid-run and wait for the next cold start. Each execution is therefore
 * bounded two ways and resumes via an appended continuation (see
 * `IndexingScheduler.enqueueContinuation`):
 * - at most [MAX_PHOTOS_PER_EXECUTION] photos per execution;
 * - at most [RUN_TIME_BUDGET_MS] wall-clock time per execution (headroom
 *   before the 10-minute cap so the slice stops itself instead of being
 *   killed).
 *
 * All helpers here are pure so they are pinned by plain-JVM tests (see
 * `RunSliceTest`); the worker only maps them onto `Result` and the pipeline
 * maps them onto photo lists.
 */

/** Input-data flag distinguishing a fresh library pass from its tail slices. */
const val CONTINUATION_KEY = "is_continuation"

/**
 * Photos settled per worker execution. At ~2–8s/photo on-device this keeps
 * even a slow slice (~7 min worst case) under the 10-minute cap; the time
 * budget below aborts earlier on slower hardware.
 */
const val MAX_PHOTOS_PER_EXECUTION = 50

/**
 * Wall-clock budget per execution. Eight minutes leaves ~2 minutes of
 * headroom before WorkManager's ~10-minute stop so the slice yields
 * cooperatively (via `shouldStopSlice`) instead of being killed.
 */
const val RUN_TIME_BUDGET_MS = 8L * 60L * 1000L

/**
 * Cooperative stop check evaluated before each photo in a slice: stop when
 * WorkManager already asked ([isStopped]) or the time budget is spent.
 */
fun shouldStopSlice(elapsedMs: Long, isStopped: Boolean, budgetMs: Long = RUN_TIME_BUDGET_MS): Boolean =
    isStopped || elapsedMs >= budgetMs

/**
 * Photos in this pass not yet attempted: fresh pending minus URIs already
 * settled (succeeded or recorded as failures) by earlier slices of the same
 * pass. Filtering stops terminally-failing photos from starving the tail —
 * without it every slice would retry the same head failures and never reach
 * later photos. Previous-pass failures are NOT excluded: a fresh pass starts
 * with an empty log (first slice replaces), so they are retried with a fresh
 * budget.
 */
fun filterUnattempted(pendingUris: List<String>, loggedUris: Set<String>): List<String> =
    pendingUris.filter { it !in loggedUris }

/**
 * Whether the pass needs another execution after this slice: true while any
 * unattempted photo from this pass remains unsettled (count bound left a
 * tail, or the slice stopped early on time/stop).
 */
fun needsContinuation(filteredTotal: Int, settled: Int): Boolean =
    settled < filteredTotal
