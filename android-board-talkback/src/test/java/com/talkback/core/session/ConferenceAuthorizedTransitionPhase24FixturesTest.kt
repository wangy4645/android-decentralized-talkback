package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2-4 contract fixtures AT1–AT8 (user T1–T8).
 * Exit: AT1–AT8 PASS → 2-4 contract PASS → Authority emit NOT AUTHORIZED until signed.
 */
class ConferenceAuthorizedTransitionPhase24FixturesTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")
    private val members3 = listOf("M01", "M02", "M03")

    private fun anchorSnapshot(
        members: List<String> = members4,
        anchor: String = "M01",
        meshGeneration: Long = 31L,
        anchorEpoch: Long = 10L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = anchor,
            members = members,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    @Test
    fun at1_noTopologyChange_noTransition() {
        val snapshot = anchorSnapshot()
        val composed = ConferenceAuthorizedTransitionContract.compose(snapshot, snapshot)
        assertTrue(composed is AuthorizedTransitionCompose.None)
    }

    @Test
    fun at2_generationChange_bindsOldAndNewGeneration() {
        val previous = anchorSnapshot(members = members4, meshGeneration = 31L)
        val current = anchorSnapshot(members = members3, meshGeneration = 32L)
        val composed = ConferenceAuthorizedTransitionContract.compose(previous, current)
        assertTrue(composed is AuthorizedTransitionCompose.Emitted)
        val transition = (composed as AuthorizedTransitionCompose.Emitted).transition
        assertEquals(31L, transition.fromMeshGeneration)
        assertEquals(32L, transition.toMeshGeneration)
        assertEquals(setOf(MediaEdge("M01", "M04")), transition.affectedEdges)
    }

    @Test
    fun at3_anchorFailover_bindsOldAndNewEpoch() {
        val previous = anchorSnapshot(anchorEpoch = 10L, meshGeneration = 31L)
        val current = ConferenceTopologyContract.failoverSnapshot(previous, "M02")
        val composed = ConferenceAuthorizedTransitionContract.compose(previous, current)
        assertTrue(composed is AuthorizedTransitionCompose.Emitted)
        val transition = (composed as AuthorizedTransitionCompose.Emitted).transition
        assertEquals(10L, transition.fromAnchorEpoch)
        assertEquals(11L, transition.toAnchorEpoch)
        assertEquals(previous.actualMediaEdges, transition.affectedEdges)
    }

    @Test
    fun at4_affectedEdges_subsetOfPreviousActualMediaEdgeSet() {
        val previous = anchorSnapshot()
        val current = ConferenceTopologyContract.failoverSnapshot(previous, "M02")
        val transition = (ConferenceAuthorizedTransitionContract.compose(previous, current)
            as AuthorizedTransitionCompose.Emitted).transition
        assertTrue(previous.actualMediaEdges.containsAll(transition.affectedEdges))
        assertFalse(MediaEdge("M02", "M03") in transition.affectedEdges)
    }

    @Test
    fun at5_emptyAffectedEdges_rejectedAsWildcard() {
        val previous = anchorSnapshot()
        val current = ConferenceTopologyContract.failoverSnapshot(previous, "M02")
        val wildcard = AuthorizedTransition(
            fromMeshGeneration = previous.meshGeneration,
            toMeshGeneration = current.meshGeneration,
            fromAnchorEpoch = previous.anchorEpoch,
            toAnchorEpoch = current.anchorEpoch,
            affectedEdges = emptySet()
        )
        assertTrue(
            ConferenceRecoveryBindingContract.validateAuthorizedTransition(wildcard, current, previous)
                is TransitionValidity.Invalid
        )
    }

    @Test
    fun at6_unlistedDisplacedEdge_notRecoveryTarget() {
        val previous = anchorSnapshot()
        val current = ConferenceTopologyContract.failoverSnapshot(previous, "M02")
        val listed = MediaEdge("M01", "M02")
        val unlisted = MediaEdge("M01", "M03")
        val partial = AuthorizedTransition(
            fromMeshGeneration = 31L,
            toMeshGeneration = 32L,
            fromAnchorEpoch = 10L,
            toAnchorEpoch = 11L,
            affectedEdges = setOf(listed)
        )
        val projected = RecoveryEdgeProvider.project(current, previous, setOf(partial))
        assertTrue(ConferenceRecoveryBindingContract.admitsMediaEdge(projected, "c1", listed))
        assertFalse(ConferenceRecoveryBindingContract.admitsMediaEdge(projected, "c1", unlisted))
    }

    @Test
    fun at7_toGeneration_mustEqualCurrentSnapshot() {
        val previous = anchorSnapshot()
        val current = ConferenceTopologyContract.failoverSnapshot(previous, "M02")
        val composed = (ConferenceAuthorizedTransitionContract.compose(previous, current)
            as AuthorizedTransitionCompose.Emitted).transition
        assertEquals(current.meshGeneration, composed.toMeshGeneration)
        val wrongTo = composed.copy(toMeshGeneration = current.meshGeneration + 1L)
        assertTrue(
            ConferenceRecoveryBindingContract.validateAuthorizedTransition(wrongTo, current, previous)
                is TransitionValidity.Invalid
        )
    }

    @Test
    fun at8_provider_doesNotInferTransition() {
        val previous = anchorSnapshot()
        val current = ConferenceTopologyContract.failoverSnapshot(previous, "M02")
        val inferred = RecoveryEdgeProvider.project(current, previousSnapshot = previous)
        assertTrue(inferred.targets.none { it.source == RecoveryTargetSource.AUTHORIZED_TRANSITION })
        val composed = ConferenceAuthorizedTransitionContract.compose(previous, current)
        assertTrue(composed is AuthorizedTransitionCompose.Emitted)
    }
}
