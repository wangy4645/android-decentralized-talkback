package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConferenceNativeExecutionDomainTest {

  private val domain = ConferenceNativeExecutionDomain()

  @Test
  fun grantFirstRequest() {
    assertEquals(
      ConferenceNativeExecutionDomain.RequestResult.Granted,
      domain.requestLease(EDGE_A),
    )
    assertEquals(EDGE_A, domain.currentHolder())
    assertEquals(
      ConferenceNativeExecutionDomain.LeaseState.ACTIVE,
      domain.currentSnapshot()?.state,
    )
  }

  @Test
  fun secondEdgeGetsBusyWhileHolderActive() {
    domain.requestLease(EDGE_A)
    val result = domain.requestLease(EDGE_B)
    assertTrue(result is ConferenceNativeExecutionDomain.RequestResult.Busy)
    assertEquals(
      EDGE_A,
      (result as ConferenceNativeExecutionDomain.RequestResult.Busy).holderEdgeKey,
    )
    assertEquals(EDGE_A, domain.currentHolder())
  }

  @Test
  fun sameEdgeReentrantGrant() {
    domain.requestLease(EDGE_A)
    assertEquals(
      ConferenceNativeExecutionDomain.RequestResult.Granted,
      domain.requestLease(EDGE_A),
    )
  }

  @Test
  fun releaseAllowsNextHolder() {
    domain.requestLease(EDGE_A)
    assertTrue(domain.releaseLease(EDGE_A))
    assertNull(domain.currentHolder())
    assertEquals(
      ConferenceNativeExecutionDomain.RequestResult.Granted,
      domain.requestLease(EDGE_B),
    )
    assertEquals(EDGE_B, domain.currentHolder())
  }

  @Test
  fun releaseWrongEdgeRejected() {
    domain.requestLease(EDGE_A)
    assertFalse(domain.releaseLease(EDGE_B))
    assertEquals(EDGE_A, domain.currentHolder())
  }

  @Test
  fun stuckBlocksNewGrantsAsBusy() {
    domain.requestLease(EDGE_A)
    assertTrue(domain.markStuck(EDGE_A))
    val result = domain.requestLease(EDGE_B)
    assertTrue(result is ConferenceNativeExecutionDomain.RequestResult.Busy)
    assertEquals(EDGE_A, (result as ConferenceNativeExecutionDomain.RequestResult.Busy).holderEdgeKey)
  }

  @Test
  fun quarantineBlocksNewGrants() {
    domain.requestLease(EDGE_A)
    assertTrue(domain.markStuck(EDGE_A))
    assertTrue(domain.quarantine(EDGE_A))
    assertEquals(
      ConferenceNativeExecutionDomain.RequestResult.Quarantined,
      domain.requestLease(EDGE_B),
    )
    assertEquals(
      ConferenceNativeExecutionDomain.LeaseState.QUARANTINED,
      domain.currentSnapshot()?.state,
    )
  }

  @Test
  fun releaseRejectedWhileStuck() {
    domain.requestLease(EDGE_A)
    assertTrue(domain.markStuck(EDGE_A))
    assertFalse(domain.releaseLease(EDGE_A))
  }

  @Test
  fun concurrentSecondRequestGetsBusy() {
    val pool = Executors.newFixedThreadPool(2)
    val domain = ConferenceNativeExecutionDomain()
    val first = CountDownLatch(1)
    val secondDone = CountDownLatch(1)
    val secondResult = AtomicReference<ConferenceNativeExecutionDomain.RequestResult>()
    try {
      pool.submit {
        domain.requestLease(EDGE_A)
        first.countDown()
        Thread.sleep(200)
        domain.releaseLease(EDGE_A)
      }
      pool.submit {
        assertTrue(first.await(2, TimeUnit.SECONDS))
        secondResult.set(domain.requestLease(EDGE_B))
        secondDone.countDown()
      }
      assertTrue(secondDone.await(2, TimeUnit.SECONDS))
      assertTrue(secondResult.get() is ConferenceNativeExecutionDomain.RequestResult.Busy)
    } finally {
      pool.shutdownNow()
    }
  }

  companion object {
    private const val EDGE_A = "sess-1|M03"
    private const val EDGE_B = "sess-1|M04"
  }
}
