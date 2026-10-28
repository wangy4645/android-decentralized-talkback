package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2-1 — RecoveryEdgeProvider action validation via RecoveryEdgeActionContract. */
class RecoveryEdgeProviderDecisionTest {

    private val harness = RecoveryEdgeActionHarness()
    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun snapshot(
        anchor: String = "M01",
        meshGeneration: Long = 5L,
        anchorEpoch: Long = 10L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c-recovery",
            hostModuleId = "M01",
            anchorId = anchor,
            members = members4,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    private fun inputFor(
        snap: ConferenceTopologySnapshot,
        remote: String,
        action: RecoveryEdgeAction,
        settlement: NegotiationSettlementTruth = NegotiationSettlementTruth.SETTLED,
        commitPhase: CoordinatorCommitPhase = CoordinatorCommitPhase.COMMITTED
    ) = RecoveryEdgeDecisionInput(
        context = harness.contextForRemote(
            snapshot = snap,
            remoteModuleId = remote,
            lifecycleState = MediaExecutionLifecycleState.ACTIVE
        ),
        requestedAction = action,
        currentSnapshot = snap,
        settlement = settlement,
        commitPhase = commitPhase
    )

    @Test
    fun edgeDegraded_allowsIceRestartIntentOnly() {
        val snap = snapshot()
        val decision = RecoveryEdgeProvider.decide(
            inputFor(snap, remote = "M02", action = RecoveryEdgeAction.ICE_RESTART)
        )
        assertTrue(decision is RecoveryEdgeDecision.Allow)
        val allow = decision as RecoveryEdgeDecision.Allow
        assertEquals(RecoveryEdgeAction.ICE_RESTART, allow.action)
        assertTrue(allow.intentOnly)
        assertEquals(MediaEdge("M01", "M02"), allow.target.mediaEdge)
    }

    @Test
    fun staleEdge_rejected() {
        val snap = snapshot(meshGeneration = 8L)
        val context = harness.contextForRemote(snap, "M02").copy(
            mediaEdgeRef = TopologyMediaEdgeRef.fromSnapshot(snap, MediaEdge("M01", "M02"))
                .copy(meshGeneration = 6L)
        )
        val decision = RecoveryEdgeProvider.decide(
            RecoveryEdgeDecisionInput(
                context = context,
                requestedAction = RecoveryEdgeAction.REEVALUATE,
                currentSnapshot = snap
            )
        )
        assertTrue(decision is RecoveryEdgeDecision.Reject)
        assertEquals(
            RecoveryEdgeActionRejectCode.REJECT_STALE_RECOVERY_CONTEXT,
            (decision as RecoveryEdgeDecision.Reject).code
        )
    }

    @Test
    fun edgeNotAdmitted_defersWaitAuthority() {
        val snap = snapshot()
        val context = harness.contextForRemote(
            snapshot = snap,
            remoteModuleId = "M99"
        )
        val decision = RecoveryEdgeProvider.decide(
            RecoveryEdgeDecisionInput(
                context = context,
                requestedAction = RecoveryEdgeAction.ICE_RESTART,
                currentSnapshot = snap
            )
        )
        assertTrue(decision is RecoveryEdgeDecision.Defer)
        assertEquals(
            RecoveryEdgeDeferCode.EDGE_NOT_ADMITTED,
            (decision as RecoveryEdgeDecision.Defer).code
        )
    }

    @Test
    fun forbiddenAction_rejectedBeforeExecution() {
        val snap = snapshot()
        val decision = RecoveryEdgeProvider.decide(
            inputFor(snap, remote = "M03", action = RecoveryEdgeAction.SELECT_ANCHOR)
        )
        assertTrue(decision is RecoveryEdgeDecision.Reject)
        assertEquals(
            RecoveryEdgeActionRejectCode.REJECT_INVALID_RECOVERY_ACTION,
            (decision as RecoveryEdgeDecision.Reject).code
        )
    }

    @Test
    fun unsettledNegotiation_defersRetry() {
        val snap = snapshot()
        val decision = RecoveryEdgeProvider.decide(
            inputFor(
                snap,
                remote = "M02",
                action = RecoveryEdgeAction.RETRY_NEGOTIATION,
                settlement = NegotiationSettlementTruth.NOT_SETTLED,
                commitPhase = CoordinatorCommitPhase.NOT_COMMITTED
            )
        )
        assertTrue(decision is RecoveryEdgeDecision.Defer)
        assertEquals(
            RecoveryEdgeDeferCode.WAIT_SETTLEMENT,
            (decision as RecoveryEdgeDecision.Defer).code
        )
    }

    @Test
    fun decide_doesNotMutateSnapshot() {
        val snap = snapshot()
        val before = snap.copy(
            members = snap.members.toList(),
            actualMediaEdges = snap.actualMediaEdges.toSet()
        )
        RecoveryEdgeProvider.decide(
            inputFor(snap, remote = "M02", action = RecoveryEdgeAction.ICE_RESTART)
        )
        assertEquals(before, snap)
    }
}
