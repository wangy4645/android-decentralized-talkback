package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceNativeDomainIdleObservabilityTest {

    private val domain = ConferenceNativeExecutionDomain()
    private val sessionId = "sess-1"

    private fun ctx(remote: String) = ConferenceSrdObservability.Context(
        sessionId = sessionId,
        remoteModuleId = remote,
        localModuleId = "M01",
        pcGeneration = 3L,
        conferenceGeneration = 1L,
    )

    private fun edge(remote: String) = "$sessionId|$remote"

    private fun begin(remote: String) {
        ConferenceSrdNativeObservability.beginAttempt(
            ctx = ctx(remote),
            conferenceGeneration = 1L,
            answerSdp = "v=0\n",
        )
    }

    @Test
    fun normalPath_e3ChainObservedThroughRunWithLease() {
        val lines = mutableListOf<String>()
        val log: (String) -> Unit = { lines += it }
        val edgeKey = edge("M02")
        begin("M02")

        val outcome = ConferenceSrdNativeDomainAdmission.runWithLease(
            domain = domain,
            edgeKey = edgeKey,
            logLine = log,
        ) {
            ConferenceSrdNativeDomainObservability.recordLeaseGranted(edgeKey)
            ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(edgeKey)
            ConferenceSrdNativeObservability.record(edgeKey, "SRD_NATIVE_CALL_ENTER")
            ConferenceSrdNativeObservability.recordNativeCallExit(edgeKey, elapsedMs = 5L)
            ConferenceSrdNativeDomainObservability.recordDomainExecutionExit(edgeKey, elapsedMs = 12L)
            ConferenceSrdNativeObservability.record(edgeKey, "SRD_EXIT")
        }

        assertTrue(outcome is ConferenceSrdNativeDomainAdmission.Outcome.Completed)
        assertEquals("NONE", ConferenceNativeDomainIdleObservability.queryHolderFact(domain).holderEdgeKey)

        assertTrue(lines.any { it.startsWith("NATIVE_DOMAIN_LEASE_RELEASE") && it.contains("released=true") })
        assertTrue(lines.any { it.contains("NATIVE_DOMAIN_HOLDER_SNAPSHOT") && it.contains("trigger=LEASE_RELEASE") && it.contains("holderEdgeKey=NONE") })
        assertTrue(lines.any { it.startsWith("DOMAIN_IDLE_E3") && it.contains("edgeKey=$edgeKey") })

        val releaseIdx = lines.indexOfFirst { it.startsWith("NATIVE_DOMAIN_LEASE_RELEASE") }
        val snapshotIdx = lines.indexOfLast { it.contains("trigger=LEASE_RELEASE") }
        val e3Idx = lines.indexOfFirst { it.startsWith("DOMAIN_IDLE_E3") }
        assertTrue(releaseIdx >= 0 && snapshotIdx > releaseIdx && e3Idx > snapshotIdx)
    }

    @Test
    fun hangPath_noLeaseRelease_noDomainIdleE3_holderRetained() {
        val lines = mutableListOf<String>()
        val log: (String) -> Unit = { lines += it }
        val m03 = edge("M03")
        begin("M03")

        domain.requestLease(m03)
        ConferenceSrdNativeDomainObservability.recordLeaseGranted(m03)
        ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(m03)
        ConferenceSrdNativeObservability.record(m03, "SRD_NATIVE_CALL_ENTER")

        ConferenceNativeDomainIdleObservability.logHolderSnapshot(
            domain = domain,
            trigger = "WATCHDOG_GAP",
            contextEdgeKey = m03,
            logLine = log,
        )

        val fact = ConferenceNativeDomainIdleObservability.queryHolderFact(domain)
        assertEquals(m03, fact.holderEdgeKey)
        assertEquals("ACTIVE", fact.occupancyState)
        assertEquals("NATIVE_CALL_ACTIVE", fact.executionState)
        assertFalse(lines.any { it.startsWith("NATIVE_DOMAIN_LEASE_RELEASE") })
        assertFalse(lines.any { it.startsWith("DOMAIN_IDLE_E3") })
        assertTrue(
            lines.any {
                it.contains("trigger=WATCHDOG_GAP") &&
                    it.contains("holderEdgeKey=$m03") &&
                    it.contains("executionState=NATIVE_CALL_ACTIVE")
            },
        )
    }

    @Test
    fun multiEdge_m03Holder_m04BusySnapshotShowsM03() {
        val lines = mutableListOf<String>()
        val log: (String) -> Unit = { lines += it }
        val m03 = edge("M03")
        val m04 = edge("M04")
        begin("M03")
        begin("M04")

        domain.requestLease(m03)
        ConferenceSrdNativeDomainObservability.recordLeaseGranted(m03)
        ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(m03)
        ConferenceSrdNativeObservability.record(m03, "SRD_NATIVE_CALL_ENTER")

        val outcome = ConferenceSrdNativeDomainAdmission.runWithLease(
            domain = domain,
            edgeKey = m04,
            logLine = log,
        ) { Unit }

        assertTrue(outcome is ConferenceSrdNativeDomainAdmission.Outcome.Busy)
        assertEquals(m03, (outcome as ConferenceSrdNativeDomainAdmission.Outcome.Busy).holderEdgeKey)

        val busySnapshot = lines.last { it.contains("trigger=LEASE_BUSY") }
        assertTrue(busySnapshot.contains("contextEdgeKey=$m04"))
        assertTrue(busySnapshot.contains("holderEdgeKey=$m03"))
        assertTrue(busySnapshot.contains("occupancyState=ACTIVE"))
        assertFalse(lines.any { it.startsWith("DOMAIN_IDLE_E3") })
    }

    @Test
    fun stuckReleaseRejected_noE3_holderRetained() {
        val lines = mutableListOf<String>()
        val log: (String) -> Unit = { lines += it }
        val edgeKey = edge("M03")
        begin("M03")

        domain.requestLease(edgeKey)
        domain.markStuck(edgeKey)

        val prior = domain.currentSnapshot()
        val released = domain.releaseLease(edgeKey)
        ConferenceNativeDomainIdleObservability.logLeaseRelease(
            edgeKey = edgeKey,
            reason = ConferenceNativeExecutionDomain.ReleaseReason.NORMAL,
            released = released,
            priorSnapshot = prior,
            logLine = log,
        )
        ConferenceNativeDomainIdleObservability.tryEmitDomainIdleE3(
            edgeKey = edgeKey,
            domain = domain,
            leaseReleased = released,
            logLine = log,
        )

        assertFalse(released)
        assertEquals(edgeKey, ConferenceNativeDomainIdleObservability.queryHolderFact(domain).holderEdgeKey)
        assertTrue(lines.any { it.contains("released=false") })
        assertFalse(lines.any { it.startsWith("DOMAIN_IDLE_E3") })
    }

    @Test
    fun queryHolderFact_emptyDomain_isNone() {
        val fact = ConferenceNativeDomainIdleObservability.queryHolderFact(domain)
        assertEquals("NONE", fact.holderEdgeKey)
        assertEquals("NONE", fact.occupancyState)
        assertEquals("NONE", fact.executionState)
        assertEquals(ConferenceSrdNativeDomainObservability.DOMAIN_ID, fact.domainId)
    }
}
