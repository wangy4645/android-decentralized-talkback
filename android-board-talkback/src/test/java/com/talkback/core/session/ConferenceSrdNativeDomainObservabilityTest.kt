package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceSrdNativeDomainObservabilityTest {

  private fun ctx(remote: String = "M02") = ConferenceSrdObservability.Context(
    sessionId = "sess-1",
    remoteModuleId = remote,
    localModuleId = "M01",
    pcGeneration = 2L,
    conferenceGeneration = 1L,
  )

  @Test
  fun case1_normalM02LifecycleFacts() {
    val edgeKey = "sess-1|M02"
    val obs = ctx("M02")
    ConferenceSrdNativeObservability.beginAttempt(obs, conferenceGeneration = 1L, answerSdp = "v=0\n")
    ConferenceSrdNativeDomainObservability.recordLeaseRequest(edgeKey)
    ConferenceSrdNativeDomainObservability.recordLeaseGranted(edgeKey)
    ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(edgeKey)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_ENTER")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_NATIVE_CALL_ENTER")
    ConferenceSrdNativeObservability.recordNativeCallExit(edgeKey, elapsedMs = 5L)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_JNI_RETURN")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_CALLBACK_ENTER")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_MUTEX_RELEASED")
    ConferenceSrdNativeDomainObservability.recordDomainExecutionExit(edgeKey, elapsedMs = 12L)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_EXIT")

    val state = ConferenceSrdNativeObservability.peekAttempt(edgeKey)!!
    assertTrue(state.sawDomainLeaseGranted)
    assertTrue(state.sawDomainExecutionEnter)
    assertTrue(state.sawDomainExecutionExit)
    assertTrue(state.sawNativeCallExit)
    assertTrue(state.sawExit)

    assertTrue(
      ConferenceSrdNativeDomainObservability.formatLeaseGranted(obs)
        .contains("domainId=${ConferenceSrdNativeDomainObservability.DOMAIN_ID}"),
    )
    assertTrue(
      ConferenceSrdNativeDomainObservability.formatDomainExecutionEnter(obs, pcHash = 99)
        .contains("pcHash=99"),
    )
  }

  @Test
  fun case2_m03NativeStuckWatchdogClassification() {
    val edgeKey = "sess-1|M03"
    ConferenceSrdNativeObservability.beginAttempt(ctx("M03"), conferenceGeneration = 1L, answerSdp = "v=0\n")
    ConferenceSrdNativeDomainObservability.recordLeaseRequest(edgeKey)
    ConferenceSrdNativeDomainObservability.recordLeaseGranted(edgeKey)
    ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(edgeKey)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_ENTER")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_NATIVE_CALL_ENTER")

    val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey, pcHash = 42)
    assertTrue(line.contains("missing=NATIVE_CALL_EXIT"))
    assertTrue(line.contains("state=NATIVE_DOMAIN_EXECUTION_ACTIVE"))
    assertTrue(line.contains("holderEdge=sess-1|M03"))
    assertFalse(line.contains("lastEvent=NONE"))
  }

  @Test
  fun case3_m04BusyWaiterNoNativePath() {
    val m03 = "sess-1|M03"
    val m04 = "sess-1|M04"
    ConferenceSrdNativeObservability.beginAttempt(ctx("M03"), conferenceGeneration = 1L, answerSdp = "a")
    ConferenceSrdNativeObservability.beginAttempt(ctx("M04"), conferenceGeneration = 1L, answerSdp = "b")

    ConferenceSrdNativeDomainObservability.recordLeaseRequest(m03)
    ConferenceSrdNativeDomainObservability.recordLeaseGranted(m03)
    ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(m03)
    ConferenceSrdNativeObservability.record(m03, "SRD_NATIVE_CALL_ENTER")

    ConferenceSrdNativeDomainObservability.recordLeaseRequest(m04)
    ConferenceSrdNativeDomainObservability.recordLeaseBusy(m04, m03)

    val waiter = ConferenceSrdNativeObservability.peekAttempt(m04)!!
    assertEquals("NATIVE_DOMAIN_LEASE_BUSY", waiter.lastEvent)
    assertEquals(m03, waiter.domainHolderEdgeKey)
    assertFalse(waiter.sawDomainLeaseGranted)
    assertFalse(waiter.sawDomainExecutionEnter)
    assertFalse(waiter.sawNativeCallEnter)

    val busyLine = ConferenceSrdNativeDomainObservability.formatLeaseBusy(ctx("M04"), m03)
    assertTrue(busyLine.contains("holderEdgeKey=$m03"))
    assertTrue(busyLine.contains("domainId=${ConferenceSrdNativeDomainObservability.DOMAIN_ID}"))
  }

  @Test
  fun watchdogGap_domainLeaseWaitClassification() {
    val edgeKey = "sess-1|M04"
    ConferenceSrdNativeObservability.beginAttempt(ctx("M04"), conferenceGeneration = 1L, answerSdp = "v=0")
    ConferenceSrdNativeDomainObservability.recordLeaseRequest(edgeKey)
    val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey)
    assertTrue(line.contains("missing=DOMAIN_LEASE"))
    assertTrue(line.contains("state=WAITING_FOR_DOMAIN"))
  }

  @Test
  fun watchdogGap_postNativeCallbackWaitMissingSrdExit() {
    val edgeKey = "sess-1|M02"
    ConferenceSrdNativeObservability.beginAttempt(ctx(), conferenceGeneration = 1L, answerSdp = "v=0")
    ConferenceSrdNativeDomainObservability.recordLeaseGranted(edgeKey)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_JNI_RETURN")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_CALLBACK_ENTER")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_MUTEX_RELEASED")
    val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey)
    assertTrue(line.contains("missing=SRD_EXIT"))
    assertTrue(line.contains("state=POST_NATIVE_CALLBACK_WAIT"))
  }
}
