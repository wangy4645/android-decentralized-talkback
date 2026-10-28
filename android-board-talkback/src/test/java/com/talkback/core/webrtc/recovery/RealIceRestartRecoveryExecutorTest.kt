package com.talkback.core.webrtc.recovery

import com.talkback.core.session.AnchorAdmissionInput
import com.talkback.core.session.ConferenceRecoveryExecutorAdapter
import com.talkback.core.session.ConferenceTopologyContract
import com.talkback.core.session.RecoveryEdgeAction
import com.talkback.core.session.RecoveryEdgeDecisionInput
import com.talkback.core.session.RecoveryEdgeProvider
import com.talkback.core.session.RecoveryExecutionAdmission
import com.talkback.core.session.RecoveryExecutionBoundaryContract
import com.talkback.core.session.RecoveryExecutionFactSource
import com.talkback.core.session.RecoveryExecutionOutcomeKind
import com.talkback.core.session.RecoveryExecutorAdapterResult
import com.talkback.core.session.MediaExecutionLifecycleState
import com.talkback.core.session.RecoveryEdgeActionHarness
import com.talkback.core.webrtc.StubWebRtcAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-1 — single-edge ICE_RESTART runtime validation (R3A–R3E).
 *
 * Uses [StubWebRtcAudioEngine] as controlled [com.talkback.core.webrtc.WebRtcAudioEngine]
 * stand-in; same contract path applies to [com.talkback.core.webrtc.RealWebRtcAudioEngine].
 */
class RealIceRestartRecoveryExecutorTest {

    private val actionHarness = RecoveryEdgeActionHarness()
    private val engine = StubWebRtcAudioEngine(observedModuleId = "M01")
    private val executor = WebRtcIceRestartRecoveryExecutor(engine)

    private fun meshSnapshot(
        meshGeneration: Long = 10L,
        anchorEpoch: Long = 100L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c-r3",
            hostModuleId = "M01",
            anchorId = "M01",
            members = listOf("M01", "M02"),
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    private fun executionRequest(
        snapshot: com.talkback.core.session.ConferenceTopologySnapshot,
        remote: String = "M02"
    ): com.talkback.core.session.RecoveryExecutionRequest {
        val decision = RecoveryEdgeProvider.decide(
            RecoveryEdgeDecisionInput(
                context = actionHarness.contextForRemote(
                    snapshot = snapshot,
                    remoteModuleId = remote,
                    lifecycleState = MediaExecutionLifecycleState.ACTIVE
                ),
                requestedAction = RecoveryEdgeAction.ICE_RESTART,
                currentSnapshot = snapshot
            )
        )
        return (
            RecoveryExecutionBoundaryContract.admitExecutionRequest(
                consumer = RecoveryExecutionBoundaryContract.SOLE_EXECUTION_CONSUMER,
                decision = decision,
                currentSnapshot = snapshot
            ) as RecoveryExecutionAdmission.Accepted
            ).request
    }

    @Test
    fun r3a_requestRealIceRestart_producesExecutorFact() {
        val snap = meshSnapshot()
        val request = executionRequest(snap)
        val result = ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = snap,
            executor = executor,
            inFlightRequest = null
        ) as RecoveryExecutorAdapterResult.FactEmitted
        assertTrue(result.executorInvoked)
        assertEquals(RecoveryExecutionOutcomeKind.ACTION_COMPLETED, result.fact.kind)
        assertEquals(RecoveryExecutionFactSource.EXECUTOR, result.fact.factSource)
        assertTrue(result.fact.reason!!.contains("restart", ignoreCase = true))
    }

    @Test
    fun r3b_engineFailure_actionFailedNotEdgeInvalid() {
        engine.failNextIceRestart = true
        val snap = meshSnapshot()
        val request = executionRequest(snap)
        val fact = executor.execute(request)
        assertEquals(RecoveryExecutionOutcomeKind.ACTION_FAILED, fact.kind)
        assertFalse(RealIceRestartRecoveryContract.isEdgeInvalidTopologyFact(fact.reason))
    }

    @Test
    fun r3c_staleGeneration_rejectedWithoutEngineInvoke() {
        val snap = meshSnapshot(meshGeneration = 12L)
        val request = executionRequest(snap)
        val staleSnap = snap.copy(meshGeneration = 13L)
        val result = ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = staleSnap,
            executor = executor
        ) as RecoveryExecutorAdapterResult.FactEmitted
        assertFalse(result.executorInvoked)
        assertEquals(RecoveryExecutionOutcomeKind.ACTION_REJECTED, result.fact.kind)
    }

    @Test
    fun r3d_duplicateExecution_singleFlight() {
        val snap = meshSnapshot()
        val request = executionRequest(snap)
        ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = snap,
            executor = executor
        )
        val duplicate = ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = snap,
            executor = executor,
            inFlightRequest = request
        ) as RecoveryExecutorAdapterResult.FactEmitted
        assertEquals(RecoveryExecutionOutcomeKind.ACTION_REJECTED, duplicate.fact.kind)
        assertEquals(ConferenceRecoveryExecutorAdapter.DUPLICATE_IN_FLIGHT, duplicate.fact.reason)
    }

    @Test
    fun r3e_successDoesNotMutateTopologySnapshot() {
        val snap = meshSnapshot()
        val before = snap.copy(
            members = snap.members.toList(),
            actualMediaEdges = snap.actualMediaEdges.toSet()
        )
        val request = executionRequest(snap)
        ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = snap,
            executor = executor
        )
        assertEquals(before, snap)
    }

    @Test
    fun q4_iceConnectedAlone_isForbiddenSuccessPattern() {
        assertTrue(
            RealIceRestartRecoveryContract.isForbiddenIceConnectedSuccess(
                iceConnected = true,
                restartOfferCreated = false
            )
        )
        assertFalse(
            RealIceRestartRecoveryContract.isForbiddenIceConnectedSuccess(
                iceConnected = true,
                restartOfferCreated = true
            )
        )
    }
}
