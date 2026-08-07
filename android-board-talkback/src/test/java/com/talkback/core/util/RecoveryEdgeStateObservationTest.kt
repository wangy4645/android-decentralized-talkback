package com.talkback.core.util

import com.talkback.core.session.CompletionObservationProjection
import com.talkback.core.session.ConferenceEdgeKey
import com.talkback.core.session.ControlReconciliationFact
import com.talkback.core.session.EdgeRecoveryPhase
import com.talkback.core.session.EdgeRecoveryRecord
import com.talkback.core.session.EdgeReachabilitySnapshot
import com.talkback.core.session.MembershipEpochProbeDisposition
import com.talkback.core.session.RecoveryReevaluateTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecoveryEdgeStateObservationTest {

    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        logs.clear()
        RecoveryEdgeStateObservation.resetForTest(sink = { logs.add(it) })
    }

    @After
    fun tearDown() {
        RecoveryEdgeStateObservation.resetForTest()
    }

    private fun record(
        phase: EdgeRecoveryPhase,
        mediaRestored: Boolean = false,
        obligationOpenedAtMs: Long? = 1_000L
    ): EdgeRecoveryRecord =
        EdgeRecoveryRecord(
            key = ConferenceEdgeKey("sess-obs", "M01"),
            phase = phase,
            channelId = "CH-1",
            recoveryAttemptId = 7L,
            recoveryStartedAtMs = 0L,
            mediaRestored = mediaRestored,
            obligationOpenedAtMs = obligationOpenedAtMs,
            obligationGeneration = 2L
        ).also { r ->
            r.controlReconciliationFact = ControlReconciliationFact(
                controlHandshakeCompleted = true,
                sessionEpochMatched = true,
                membershipEpochConverged = true,
                membershipProbeDisposition = MembershipEpochProbeDisposition.CHECKED,
                computedAtMs = 0L,
                attemptId = r.recoveryAttemptId,
                obligationGeneration = r.obligationGeneration
            )
        }

    private fun snapshot(mediaRoute: Boolean = false) = EdgeReachabilitySnapshot(
        linkReady = true,
        peerDiscovered = true,
        peerSignalingReachable = true,
        mediaRouteConnected = mediaRoute,
        authorityReachable = true
    )

    private fun project(
        record: EdgeRecoveryRecord,
        iceConnected: Boolean,
        mediaUnavailable: Boolean = false,
        hasUncoveredDeferredIntent: Boolean = false
    ) = CompletionObservationProjection.project(
        record = record,
        snapshot = snapshot(mediaRoute = record.mediaRestored),
        iceConnected = iceConnected,
        mediaUnavailableAdvisory = mediaUnavailable,
        hasUncoveredDeferredIntent = hasUncoveredDeferredIntent
    )

    @Test
    fun derive_receivePathLive_usesL1ProviderNotTransportEvidence() {
        RecoveryEdgeStateObservation.resetForTest(
            receivePathLiveProvider = { _, _ -> true }
        )
        val r = record(phase = EdgeRecoveryPhase.ICE_RESTARTING)
        val result = project(record = r, iceConnected = false)
        val derived = RecoveryEdgeStateObservation.derive(r, result, nowMs = 10_000L)
        assertTrue(derived.receivePathLive)
        assertFalse(derived.mediaReady)
    }

    @Test
    fun derive_receivePathLive_falseWhenProviderUnset() {
        val r = record(phase = EdgeRecoveryPhase.ICE_RESTARTING, mediaRestored = true)
        val result = project(record = r, iceConnected = true)
        val derived = RecoveryEdgeStateObservation.derive(r, result, nowMs = 10_000L)
        assertFalse(derived.receivePathLive)
        assertTrue(derived.mediaReady)
    }

    @Test
    fun caseB_failedMediaResidencyWithL2_mapsSyncPendingWithReason() {
        val r = record(
            phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY,
            mediaRestored = true
        )
        val result = project(
            record = r,
            iceConnected = true,
            hasUncoveredDeferredIntent = true
        )
        val (state, reason) = RecoveryEdgeStateObservation.mapObligationState(r, result)
        assertEquals(RecoveryEdgeStateObservation.ObligationState.SYNC_PENDING, state)
        assertEquals("MEDIA_RECOVERED_BUT_INTENT_UNCOVERED", reason)
    }

    @Test
    fun attemptTerminalWithoutL2_mapsGraceObservation() {
        val r = record(phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY)
        val result = project(record = r, iceConnected = false)
        val (state, reason) = RecoveryEdgeStateObservation.mapObligationState(r, result)
        assertEquals(RecoveryEdgeStateObservation.ObligationState.GRACE_OBSERVATION, state)
        assertTrue(reason.startsWith("ATTEMPT_TERMINAL_"))
    }

    @Test
    fun format_includesStateReasonAndDuration() {
        val r = record(
            phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY,
            mediaRestored = true
        )
        val result = project(record = r, iceConnected = true)
        val derived = RecoveryEdgeStateObservation.derive(r, result, nowMs = 20_000L)
        val line = RecoveryEdgeStateObservation.format(
            record = r,
            result = result,
            derived = derived,
            trigger = RecoveryReevaluateTrigger.ICE_RESTORED,
            lastTransition = null
        )
        assertTrue(line.contains("RECOVERY_EDGE_STATE"))
        assertTrue(line.contains("obligationState=SYNC_PENDING"))
        assertTrue(line.contains("stateReason="))
        assertTrue(line.contains("durationInStateMs="))
        assertTrue(line.contains("trigger=ICE_RESTORED"))
    }

    @Test
    fun maybeEmit_dedupesIdenticalSignature() {
        val r = record(phase = EdgeRecoveryPhase.ICE_RESTARTING)
        val result = project(record = r, iceConnected = false)
        RecoveryEdgeStateObservation.maybeEmit(
            record = r,
            result = result,
            trigger = RecoveryReevaluateTrigger.ICE_RESTORED,
            nowMs = 10_000L
        )
        RecoveryEdgeStateObservation.maybeEmit(
            record = r,
            result = result,
            trigger = RecoveryReevaluateTrigger.ICE_RESTORED,
            nowMs = 12_000L
        )
        assertEquals(1, logs.size)
    }

    @Test
    fun maybeEmit_heartbeatWhileObligationOpen() {
        val r = record(phase = EdgeRecoveryPhase.ICE_RESTARTING)
        val result = project(record = r, iceConnected = false)
        RecoveryEdgeStateObservation.maybeEmit(
            record = r,
            result = result,
            trigger = RecoveryReevaluateTrigger.ICE_RESTORED,
            nowMs = 10_000L
        )
        RecoveryEdgeStateObservation.maybeEmit(
            record = r,
            result = result,
            trigger = RecoveryReevaluateTrigger.ICE_RESTORED,
            nowMs = 16_000L
        )
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.contains("RECOVERY_EDGE_STATE") })
    }

    @Test
    fun closedObligationRecovered_mapsConverged() {
        val r = record(phase = EdgeRecoveryPhase.RECOVERED, mediaRestored = true)
        r.obligationClosedAtMs = 5_000L
        val result = project(record = r, iceConnected = true)
        assertFalse(result.obligationOpen)
        val (state, reason) = RecoveryEdgeStateObservation.mapObligationState(r, result)
        assertEquals(RecoveryEdgeStateObservation.ObligationState.CONVERGED, state)
        assertEquals("EDGE_RECOVERED", reason)
    }
}
