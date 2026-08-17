package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupLatePeerAdmissionSupportTest {

    private fun baseInput(
        peer: String = "M03",
        channel: String = "CH-01",
        canonical: Boolean = false,
        owner: Boolean = true,
        topologyReadiness: String = "OPERATIONAL"
    ) = GroupLatePeerAdmissionSupport.CandidateInput(
        peerModuleId = peer,
        helloChannelId = channel,
        sessionChannelId = channel,
        hasAcceptedGroupSession = true,
        peerInCanonicalRoster = canonical,
        isAdmissionOwner = owner,
        topologyReadiness = topologyReadiness,
        peerIsLocal = false
    )

    @Test
    fun operationalNonCanonicalOwner_admits() {
        val decision = GroupLatePeerAdmissionSupport.evaluate(baseInput())
        assertTrue(decision is GroupLatePeerAdmissionSupport.Decision.Admit)
        assertEquals("M03", (decision as GroupLatePeerAdmissionSupport.Decision.Admit).peerModuleId)
    }

    @Test
    fun buildingNonCanonicalOwner_admits() {
        val decision = GroupLatePeerAdmissionSupport.evaluate(baseInput(topologyReadiness = "BUILDING"))
        assertTrue(decision is GroupLatePeerAdmissionSupport.Decision.Admit)
        assertEquals("M03", (decision as GroupLatePeerAdmissionSupport.Decision.Admit).peerModuleId)
    }

    @Test
    fun discovering_skips() {
        val decision = GroupLatePeerAdmissionSupport.evaluate(baseInput(topologyReadiness = "DISCOVERING"))
        assertEquals(
            GroupLatePeerAdmissionSupport.SkipReason.TOPOLOGY_DISCOVERING,
            (decision as GroupLatePeerAdmissionSupport.Decision.Skip).reason
        )
    }

    @Test
    fun membershipPending_skips() {
        val decision = GroupLatePeerAdmissionSupport.evaluate(
            baseInput(topologyReadiness = "MEMBERSHIP_PENDING")
        )
        assertEquals(
            GroupLatePeerAdmissionSupport.SkipReason.TOPOLOGY_MEMBERSHIP_PENDING,
            (decision as GroupLatePeerAdmissionSupport.Decision.Skip).reason
        )
    }

    @Test
    fun alreadyCanonical_skips() {
        val decision = GroupLatePeerAdmissionSupport.evaluate(baseInput(canonical = true))
        assertEquals(
            GroupLatePeerAdmissionSupport.SkipReason.ALREADY_CANONICAL,
            (decision as GroupLatePeerAdmissionSupport.Decision.Skip).reason
        )
    }

    @Test
    fun notAdmissionOwner_skips() {
        val decision = GroupLatePeerAdmissionSupport.evaluate(baseInput(owner = false))
        assertEquals(
            GroupLatePeerAdmissionSupport.SkipReason.NOT_AUTHORITY_OR_OFFERER,
            (decision as GroupLatePeerAdmissionSupport.Decision.Skip).reason
        )
    }

    @Test
    fun channelMismatch_skips() {
        val decision = GroupLatePeerAdmissionSupport.evaluate(
            baseInput().copy(helloChannelId = "CH-OTHER")
        )
        assertEquals(
            GroupLatePeerAdmissionSupport.SkipReason.CHANNEL_MISMATCH,
            (decision as GroupLatePeerAdmissionSupport.Decision.Skip).reason
        )
    }

    @Test
    fun noGroupSession_skips() {
        val decision = GroupLatePeerAdmissionSupport.evaluate(
            baseInput().copy(hasAcceptedGroupSession = false)
        )
        assertEquals(
            GroupLatePeerAdmissionSupport.SkipReason.NO_GROUP_SESSION,
            (decision as GroupLatePeerAdmissionSupport.Decision.Skip).reason
        )
    }

    @Test
    fun multiLatePeers_eachEvaluatesIndependentlyWhileBuilding() {
        val roster = listOf("M04", "M05", "M06", "M07", "M08")
        val admitted = roster.map { peer ->
            GroupLatePeerAdmissionSupport.evaluate(
                baseInput(peer = peer, topologyReadiness = "BUILDING")
            )
        }
        assertEquals(5, admitted.count { it is GroupLatePeerAdmissionSupport.Decision.Admit })
        admitted.forEach { decision ->
            assertTrue(decision is GroupLatePeerAdmissionSupport.Decision.Admit)
        }
    }
}
