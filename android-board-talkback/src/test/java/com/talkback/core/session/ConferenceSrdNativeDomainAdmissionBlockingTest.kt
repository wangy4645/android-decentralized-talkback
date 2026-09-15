package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConferenceSrdNativeDomainAdmissionBlockingTest {

    private val domain = ConferenceNativeExecutionDomain()

    @Test
    fun secondEdgeWaitsForFirstEdgeNativeSection() {
        val pool = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val secondInvoked = AtomicBoolean(false)
        try {
            pool.submit {
                ConferenceSrdNativeDomainAdmission.runWithLeaseBlocking(domain, EDGE_M04) {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(firstEntered.await(500, TimeUnit.MILLISECONDS))
            pool.submit {
                val outcome = ConferenceSrdNativeDomainAdmission.runWithLeaseBlocking(
                    domain = domain,
                    edgeKey = EDGE_M02,
                    maxWaitMs = 5_000L,
                ) {
                    secondInvoked.set(true)
                }
                assertTrue(outcome is ConferenceSrdNativeDomainAdmission.Outcome.Completed)
                secondCompleted.countDown()
            }
            Thread.sleep(80)
            assertFalse("M02 must wait until M04 releases domain", secondInvoked.get())
            releaseFirst.countDown()
            assertTrue(secondCompleted.await(2, TimeUnit.SECONDS))
            assertTrue(secondInvoked.get())
            assertEquals(null, domain.currentHolder())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun leaseAlwaysReleasedAfterException() {
        val outcome = runCatching {
            ConferenceSrdNativeDomainAdmission.runWithLeaseBlocking(domain, EDGE_M03) {
                throw IllegalStateException("native section failed")
            }
        }
        assertTrue(outcome.isFailure)
        assertEquals(null, domain.currentHolder())
    }

    @Test
    fun busyReturnedWhenWaitBudgetExhausted() {
        domain.requestLease(EDGE_M03)
        val invoked = AtomicInteger(0)
        val outcome = ConferenceSrdNativeDomainAdmission.runWithLeaseBlocking(
            domain = domain,
            edgeKey = EDGE_M04,
            maxWaitMs = 50L,
            pollMs = 5L,
        ) {
            invoked.incrementAndGet()
        }
        assertTrue(outcome is ConferenceSrdNativeDomainAdmission.Outcome.Busy)
        assertEquals(0, invoked.get())
        assertEquals(EDGE_M03, domain.currentHolder())
    }

    companion object {
        private const val EDGE_M02 = "sess-1|M02"
        private const val EDGE_M03 = "sess-1|M03"
        private const val EDGE_M04 = "sess-1|M04"
    }
}
