package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-1 Commit 3: domain + SRD lifecycle fact chains for field adjudication.
 */
class ConferenceSrdNativeDomainObservabilityLifecycleTest {

  private fun ctx(remote: String) = ConferenceSrdObservability.Context(
    sessionId = "sess-1",
    remoteModuleId = remote,
    localModuleId = "M01",
    pcGeneration = 3L,
    conferenceGeneration = 1L,
  )

  private fun begin(remote: String): String {
    val edgeKey = "sess-1|$remote"
    ConferenceSrdNativeObservability.beginAttempt(ctx(remote), conferenceGeneration = 1L, answerSdp = "v=0\n")
    return edgeKey
  }

  @Test
  fun case1_normalM02LifecycleFacts() {
    val edgeKey = begin("M02")
    ConferenceSrdNativeDomainObservability.recordLeaseRequest(edgeKey)
    ConferenceSrdNativeDomainObservability.recordLeaseGranted(edgeKey)
    ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(edgeKey)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_ENTER")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_NATIVE_CALL_ENTER")
    ConferenceSrdNativeObservability.recordNativeCallExit(edgeKey, elapsedMs = 5L)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_JNI_RETURN")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_CALLBACK_ENTER")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_MUTEX_RELEASED")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_EXIT")
    ConferenceSrdNativeDomainObservability.recordDomainExecutionExit(edgeKey, elapsedMs = 12L)

    val state = ConferenceSrdNativeObservability.peekAttempt(edgeKey)!!
    assertTrue(state.sawDomainLeaseGranted)
    assertTrue(state.sawDomainExecutionEnter)
    assertTrue(state.sawDomainExecutionExit)
    assertTrue(state.sawNativeCallEnter)
    assertTrue(state.sawNativeCallExit)
    assertTrue(state.sawExit)
  }

  @Test
  fun case2_m03NativeStuckWatchdogClassification() {
    val edgeKey = begin("M03")
    ConferenceSrdNativeDomainObservability.recordLeaseRequest(edgeKey)
    ConferenceSrdNativeDomainObservability.recordLeaseGranted(edgeKey)
    ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(edgeKey)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_ENTER")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_NATIVE_CALL_ENTER")

    val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey, pcHash = 999)
    assertTrue(line.contains("missing=NATIVE_CALL_EXIT"))
    assertTrue(line.contains("state=NATIVE_DOMAIN_EXECUTION_ACTIVE"))
    assertTrue(line.contains("holderEdge=sess-1|M03"))
    assertFalse(line.contains("lastEvent=NONE"))
  }

  @Test
  fun case3_m04BusyWaiterNoSilentTimeoutShape() {
    val m03 = begin("M03")
    val m04 = begin("M04")
    ConferenceSrdNativeDomainObservability.recordLeaseRequest(m03)
    ConferenceSrdNativeDomainObservability.recordLeaseGranted(m03)
    ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(m03)
    ConferenceSrdNativeObservability.record(m03, "SRD_NATIVE_CALL_ENTER")

    ConferenceSrdNativeDomainObservability.recordLeaseRequest(m04)
    ConferenceSrdNativeDomainObservability.recordLeaseBusy(m04, m03)

    val waiter = ConferenceSrdNativeObservability.peekAttempt(m04)!!
    assertTrue(waiter.sawDomainLeaseBusy)
    assertTrue(waiter.lastEvent == "NATIVE_DOMAIN_LEASE_BUSY")
    assertTrue(waiter.domainHolderEdgeKey == m03)
    assertFalse(waiter.sawNativeCallEnter)
    assertFalse(waiter.sawDomainExecutionEnter)

    val busyLine = ConferenceSrdNativeDomainObservability.formatLeaseBusy(ctx("M04"), m03)
    assertTrue(busyLine.contains("holderEdgeKey=sess-1|M03"))
    assertTrue(busyLine.contains("domainId=shared-factory"))
  }

  @Test
  fun domainWaitingClassificationWhenLeaseNeverGranted() {
    val edgeKey = begin("M03")
    ConferenceSrdNativeDomainObservability.recordLeaseRequest(edgeKey)
    val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey)
    assertTrue(line.contains("missing=DOMAIN_LEASE"))
    assertTrue(line.contains("state=WAITING_FOR_DOMAIN"))
    assertFalse(line.contains("lastEvent=NONE"))
  }

  @Test
  fun postNativeCallbackWaitWhenExitMissing() {
    val edgeKey = begin("M02")
    ConferenceSrdNativeDomainObservability.recordLeaseGranted(edgeKey)
    ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(edgeKey)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_NATIVE_CALL_ENTER")
    ConferenceSrdNativeObservability.recordNativeCallExit(edgeKey, elapsedMs = 1L)
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_JNI_RETURN")
    ConferenceSrdNativeObservability.record(edgeKey, "SRD_CALLBACK_ENTER")

    val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey)
    assertTrue(line.contains("missing=SRD_EXIT"))
    assertTrue(line.contains("state=POST_NATIVE_CALLBACK_WAIT"))
  }
}
