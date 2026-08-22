package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConferenceSrdNativeDomainAdmissionTest {

  private val domain = ConferenceNativeExecutionDomain()

  @Test
  fun caseA_grantPathInvokesBlockAndReleasesSlot() {
    var srdPathInvoked = false
    val outcome = ConferenceSrdNativeDomainAdmission.runWithLease(domain, EDGE_M02) {
      srdPathInvoked = true
      Unit
    }
    assertTrue(outcome is ConferenceSrdNativeDomainAdmission.Outcome.Completed)
    assertTrue(srdPathInvoked)
    assertNull(domain.currentHolder())
  }

  @Test
  fun caseB_busyPathDoesNotInvokeBlock() {
    domain.requestLease(EDGE_M03)
    val srdPathInvoked = AtomicBoolean(false)
    val outcome = ConferenceSrdNativeDomainAdmission.runWithLease(domain, EDGE_M04) {
      srdPathInvoked.set(true)
      Unit
    }
    assertTrue(outcome is ConferenceSrdNativeDomainAdmission.Outcome.Busy)
    assertEquals(EDGE_M03, (outcome as ConferenceSrdNativeDomainAdmission.Outcome.Busy).holderEdgeKey)
    assertFalse("M04 must not enter SRD path when domain busy", srdPathInvoked.get())
    assertEquals(EDGE_M03, domain.currentHolder())
  }

  @Test
  fun caseC_exceptionReleasesSlot() {
    val outcome = runCatching {
      ConferenceSrdNativeDomainAdmission.runWithLease(domain, EDGE_M03) {
        throw IllegalStateException("SRD path failed")
      }
    }
    assertTrue(outcome.isFailure)
    assertNull("lease must not leak after SRD path exception", domain.currentHolder())
  }

  @Test
  fun quarantinedDoesNotInvokeBlock() {
    domain.requestLease(EDGE_M03)
    domain.markStuck(EDGE_M03)
    domain.quarantine(EDGE_M03)
    val invoked = AtomicInteger(0)
    val outcome = ConferenceSrdNativeDomainAdmission.runWithLease(domain, EDGE_M04) {
      invoked.incrementAndGet()
      Unit
    }
    assertEquals(ConferenceSrdNativeDomainAdmission.Outcome.Quarantined, outcome)
    assertEquals(0, invoked.get())
  }

  companion object {
    private const val EDGE_M02 = "sess-1|M02"
    private const val EDGE_M03 = "sess-1|M03"
    private const val EDGE_M04 = "sess-1|M04"
  }
}
