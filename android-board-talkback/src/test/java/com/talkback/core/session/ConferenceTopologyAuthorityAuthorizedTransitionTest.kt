package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceTopologyAuthorityAuthorizedTransitionTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")

    private fun input(
        meshGeneration: Long = 31L,
        anchorEpoch: Long = 10L,
        members: List<String> = roster4,
        anchor: String = "M01"
    ) = AnchorAdmissionInput(
        conferenceId = "c1",
        hostModuleId = "M01",
        anchorId = anchor,
        members = members,
        meshGeneration = meshGeneration,
        anchorEpoch = anchorEpoch
    )

    @Test
    fun firstPublish_noAuthorizedTransition() {
        val authority = ConferenceTopologyAuthority()
        val result = authority.publishTopologyProjection(input()) as
            ConferenceTopologyAuthority.PublishResult.Published
        assertNull(result.authorizedTransition)
        assertNull(authority.currentAuthorizedTransition("c1"))
        assertNull(authority.previousSnapshot("c1"))
    }

    @Test
    fun failover_emitsTransitionFromAuthorityPreviousCurrent() {
        val authority = ConferenceTopologyAuthority()
        authority.publishTopologyProjection(input())
        val previous = authority.currentSnapshot("c1")!!
        val result = authority.publishAnchorFailover("c1", "M02") as
            ConferenceTopologyAuthority.PublishResult.Published
        val transition = result.authorizedTransition
        assertTrue(transition != null)
        assertEquals(31L, transition!!.fromMeshGeneration)
        assertEquals(32L, transition.toMeshGeneration)
        assertEquals(10L, transition.fromAnchorEpoch)
        assertEquals(11L, transition.toAnchorEpoch)
        assertEquals(previous.actualMediaEdges, transition.affectedEdges)
        assertEquals(previous, authority.previousSnapshot("c1"))
        assertEquals(transition, authority.currentAuthorizedTransition("c1"))
        val projected = RecoveryEdgeProvider.project(
            result.snapshot,
            authority.previousSnapshot("c1"),
            setOf(transition)
        )
        assertTrue(
            ConferenceRecoveryBindingContract.admitsMediaEdge(
                projected,
                "c1",
                MediaEdge("M01", "M02")
            )
        )
    }

    @Test
    fun unchangedPublish_doesNotComposeTransition() {
        val authority = ConferenceTopologyAuthority()
        authority.publishTopologyProjection(input())
        assertTrue(
            authority.publishTopologyProjection(input())
                is ConferenceTopologyAuthority.PublishResult.Unchanged
        )
        assertNull(authority.currentAuthorizedTransition("c1"))
    }

    @Test
    fun rosterExpansion_noDisplacedEdges_noTransition() {
        val authority = ConferenceTopologyAuthority()
        authority.publishTopologyProjection(input(meshGeneration = 5L))
        val result = authority.publishRosterEdgeChange("c1", roster4 + "M05") as
            ConferenceTopologyAuthority.PublishResult.Published
        assertNull(result.authorizedTransition)
        assertEquals(6L, result.snapshot.meshGeneration)
    }
}
