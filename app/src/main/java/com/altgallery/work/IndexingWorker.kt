package com.altgallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.altgallery.permissions.MediaPermissions
import com.altgallery.processing.IndexingPipeline
import com.altgallery.processing.ModelsMissingException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/**
 * Runs one indexing pass over the pending library photos in the background.
 *
 * Behavior contract (INDX-004):
 * - No photo access → succeed quietly with no work (denied permission is a
 *   normal state, not an error; the home screen still loads its count).
 * - Missing models ([ModelsMissingException]) → succeed without retry: the
 *   condition is stable, so retrying would loop forever on the same error.
 * - Transient throw → [Result.retry] with the scheduler's backoff. A killed
 *   run is safe to repeat: completed photos are skipped via the
 *   already-processed definition, failed ones stay pending.
 * - Cancellation always propagates (including from the per-photo pause), so
 *   a replaced run stops promptly.
 *
 * Responsiveness: [CoroutineWorker] runs on a background dispatcher, the
 * repositories already confine I/O off the main thread, and the per-photo
 * callback yields — so a few-hundred-photo run keeps the screen rendering
 * and the home count (a Room `Flow`) climbing without a freeze. Progress is
 * published via `setProgress` for future UI; the home count itself updates
 * through the database, not through this channel.
 *
 * Test scope: `WorkManager`/`Context` need a device, so this is verified
 * on-device per the ticket's acceptance criteria; the policy it invokes
 * ([IndexingPipeline.runOnce] → batch runner) is pinned by plain-JVM tests.
 */
@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val indexingPipeline: IndexingPipeline,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!MediaPermissions.hasReadImages(applicationContext)) return Result.success()
        return try {
            indexingPipeline.runOnce { done, total ->
                setProgress(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
                yield()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelsMissingException) {
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
    }
}
