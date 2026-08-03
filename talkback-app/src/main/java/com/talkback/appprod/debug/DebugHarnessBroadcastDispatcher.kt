package com.talkback.appprod.debug

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DEBUG harness broadcast off-main-thread dispatch (ANR avoidance).
 *
 * Does not change recovery semantics — only how debug control intents are executed.
 *
 * Contract: [finishPending] is invoked within [MAX_WAIT_MS] even if [work] hangs
 * (separate watchdog). Without this, goAsync() still ANRs after ~60s.
 */
object DebugHarnessBroadcastDispatcher {
    const val MAX_WAIT_MS: Long = 3_000L

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "talkback-debug-bcast-watchdog").apply { isDaemon = true }
    }

    enum class Outcome {
        COMPLETED,
        TIMEOUT,
        SKIPPED,
        FAILED
    }

    /**
     * Returns immediately after scheduling [work] on [executor].
     * [finishPending] runs at most once, within [MAX_WAIT_MS].
     */
    fun schedule(
        executor: Executor,
        finishPending: () -> Unit,
        work: () -> Outcome,
        log: (String) -> Unit,
        action: String
    ) {
        val finished = AtomicBoolean(false)
        fun finishOnce(timeoutNote: String? = null) {
            if (!finished.compareAndSet(false, true)) return
            if (timeoutNote != null) {
                runCatching { log(timeoutNote) }
            }
            runCatching { finishPending() }
        }

        val watchdogFuture = watchdog.schedule(
            {
                finishOnce(
                    "DEBUG_DISPATCH_TIMEOUT action=$action " +
                        "timeoutMs=$MAX_WAIT_MS reason=watchdog_finish"
                )
            },
            MAX_WAIT_MS,
            TimeUnit.MILLISECONDS
        )

        executor.execute {
            try {
                val outcome = work()
                // Only log outcome if we still own finish (work beat the watchdog).
                if (!finished.get()) {
                    log("DEBUG_DISPATCH_$outcome action=$action")
                }
            } catch (t: Throwable) {
                if (!finished.get()) {
                    log(
                        "DEBUG_DISPATCH_FAILED action=$action " +
                            "error=${t.javaClass.simpleName}:${t.message}"
                    )
                }
            } finally {
                watchdogFuture.cancel(false)
                finishOnce()
            }
        }
    }

    /**
     * UT helper: assert schedule returns before [work] completes.
     * @return true when caller returned before work finished.
     */
    fun scheduleDoesNotBlockCaller(
        executor: Executor,
        workDurationMs: Long,
        waitForWorkMs: Long = 2_000L
    ): Boolean {
        val workStarted = AtomicBoolean(false)
        val workFinished = CountDownLatch(1)
        schedule(
            executor = executor,
            finishPending = { workFinished.countDown() },
            work = {
                workStarted.set(true)
                Thread.sleep(workDurationMs)
                Outcome.COMPLETED
            },
            log = {},
            action = "UT_PROBE"
        )
        val callerReturnedBeforeFinish = workFinished.count > 0
        workFinished.await(waitForWorkMs, TimeUnit.MILLISECONDS)
        return callerReturnedBeforeFinish && workStarted.get()
    }

    /**
     * UT helper: even if work hangs past [MAX_WAIT_MS], finishPending must still run.
     */
    fun scheduleFinishesWithinWatchdogEvenIfWorkHangs(
        executor: Executor,
        hangMs: Long = MAX_WAIT_MS + 2_000L,
        waitForFinishMs: Long = MAX_WAIT_MS + 1_500L
    ): Boolean {
        val finished = CountDownLatch(1)
        schedule(
            executor = executor,
            finishPending = { finished.countDown() },
            work = {
                Thread.sleep(hangMs)
                Outcome.COMPLETED
            },
            log = {},
            action = "UT_WATCHDOG"
        )
        return finished.await(waitForFinishMs, TimeUnit.MILLISECONDS)
    }
}
