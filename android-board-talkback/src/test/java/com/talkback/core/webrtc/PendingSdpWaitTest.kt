package com.talkback.core.webrtc

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingSdpWaitTest {

    @Test
    fun abort_unblocksWaitWithoutWaitingTimeout() {
        val wait = PendingSdpWait(timeoutMs = 5_000L, pollMs = 20L)
        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val startedAt = System.nanoTime()
            val future = executor.submit {
                wait.await(latch, "should not timeout")
            }
            Thread.sleep(40)
            wait.abort()
            try {
                future.get(500, TimeUnit.MILLISECONDS)
                fail("abort should throw")
            } catch (e: java.util.concurrent.ExecutionException) {
                assertTrue(e.cause is IllegalStateException)
                assertTrue(e.cause!!.message!!.contains("Aborted pending SDP"))
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("abort must not wait the 5s timeout elapsedMs=$elapsedMs", elapsedMs < 1_000)
        } finally {
            executor.shutdownNow()
        }
    }
}
