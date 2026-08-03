package com.talkback.appprod.debug

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ANR contract for Joint debug control plane (PR52C / D1 / SUPPRESS).
 * All DEBUG broadcasts must use [DebugHarnessBroadcastDispatcher] — never
 * block BroadcastReceiver.onReceive on coordinator sync work.
 */
class DebugHarnessBroadcastDispatcherTest {
    @Test
    fun schedule_returnsBeforeWorkCompletes_onCallerThread() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val ok = DebugHarnessBroadcastDispatcher.scheduleDoesNotBlockCaller(
                executor = executor,
                workDurationMs = 250L,
                waitForWorkMs = 2_000L
            )
            assertTrue("onReceive path must not wait for debug work", ok)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun schedule_finishesPendingResult_evenWhenWorkHangs() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val ok = DebugHarnessBroadcastDispatcher.scheduleFinishesWithinWatchdogEvenIfWorkHangs(
                executor = executor
            )
            assertTrue(
                "goAsync PendingResult.finish must run within MAX_WAIT_MS even if work hangs",
                ok
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
