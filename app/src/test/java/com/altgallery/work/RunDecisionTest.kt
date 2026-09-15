package com.altgallery.work

import com.altgallery.processing.ModelsMissingException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the worker's throw→disposition table: missing models always settle
 * the run as done, transient throws retry inside the run-level budget, and a
 * spent budget fails instead of retrying forever. The worker body only maps
 * this table onto `Result`, so pinning the table pins the behavior.
 */
class RunDecisionTest {

    @Test
    fun `models-missing always succeeds, even on the last attempt`() {
        assertEquals(RunAction.SUCCEED, actionForRunThrow(ModelsMissingException("m"), 0))
        assertEquals(
            RunAction.SUCCEED,
            actionForRunThrow(ModelsMissingException("m"), MAX_RUN_ATTEMPTS - 1),
        )
        assertEquals(RunAction.SUCCEED, actionForRunThrow(ModelsMissingException("m"), 99))
    }

    @Test
    fun `transient throw retries inside the budget`() {
        assertEquals(RunAction.RETRY, actionForRunThrow(IllegalStateException("db"), 0))
        assertEquals(
            RunAction.RETRY,
            actionForRunThrow(IllegalStateException("db"), MAX_RUN_ATTEMPTS - 2),
        )
    }

    @Test
    fun `exactly five executions before giving up`() {
        (0 until MAX_RUN_ATTEMPTS - 1).forEach {
            assertEquals(RunAction.RETRY, actionForRunThrow(IllegalStateException("db"), it))
        }
        assertEquals(
            RunAction.FAIL,
            actionForRunThrow(IllegalStateException("db"), MAX_RUN_ATTEMPTS - 1),
        )
        assertEquals(RunAction.FAIL, actionForRunThrow(IllegalStateException("db"), 99))
    }
}
