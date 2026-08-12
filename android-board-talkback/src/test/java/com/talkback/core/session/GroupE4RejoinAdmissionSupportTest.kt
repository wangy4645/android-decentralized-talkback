package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupE4RejoinAdmissionSupportTest {

    private val m02 = ModuleId("M02")
    private val endpoint = EndpointAddress(m02, EndpointId("E01"))

    @Test
    fun evaluate_issuesInvite_whenFormerlyAdmittedReachableAndAuthorityAdmissible() {
        val decisions = GroupE4RejoinAdmissionSupport.evaluate(
            GroupE4RejoinAdmissionSupport.EvaluationInput(
                admittedPeerHistory = mapOf("M02" to FormerAdmittedPeer(endpoint, prunedAtEpoch = 2L)),
                canonicalModuleIds = setOf("M01", "M03"),
                pendingInviteeModuleIds = emptySet(),
                reachableModuleIds = setOf("M02"),
                authorityAdmissible = true,
                isMembershipAuthority = true
            )
        )
        assertEquals(1, decisions.size)
        val invite = decisions.single() as GroupE4RejoinAdmissionSupport.E4RejoinAdmissionDecision.IssueInvite
        assertEquals("M02", invite.moduleId)
        assertEquals(endpoint, invite.endpoint)
    }

    @Test
    fun evaluate_defersWhenAuthorityNotAdmissible() {
        val decisions = GroupE4RejoinAdmissionSupport.evaluate(
            GroupE4RejoinAdmissionSupport.EvaluationInput(
                admittedPeerHistory = mapOf("M02" to FormerAdmittedPeer(endpoint, prunedAtEpoch = 2L)),
                canonicalModuleIds = setOf("M01"),
                pendingInviteeModuleIds = emptySet(),
                reachableModuleIds = setOf("M02"),
                authorityAdmissible = false,
                isMembershipAuthority = true
            )
        )
        assertEquals(1, decisions.size)
        val deferred = decisions.single() as GroupE4RejoinAdmissionSupport.E4RejoinAdmissionDecision.Deferred
        assertEquals("AUTHORITY_NOT_ADMISSIBLE", deferred.reason)
    }

    @Test
    fun evaluate_noActionWhenPeerStillInCanonicalRoster() {
        val decisions = GroupE4RejoinAdmissionSupport.evaluate(
            GroupE4RejoinAdmissionSupport.EvaluationInput(
                admittedPeerHistory = mapOf("M02" to FormerAdmittedPeer(endpoint, prunedAtEpoch = 2L)),
                canonicalModuleIds = setOf("M01", "M02"),
                pendingInviteeModuleIds = emptySet(),
                reachableModuleIds = setOf("M02"),
                authorityAdmissible = true,
                isMembershipAuthority = true
            )
        )
        assertTrue(decisions.isEmpty())
    }

    @Test
    fun evaluate_noActionWhenInviteAlreadyPending() {
        val decisions = GroupE4RejoinAdmissionSupport.evaluate(
            GroupE4RejoinAdmissionSupport.EvaluationInput(
                admittedPeerHistory = mapOf("M02" to FormerAdmittedPeer(endpoint, prunedAtEpoch = 2L)),
                canonicalModuleIds = setOf("M01"),
                pendingInviteeModuleIds = setOf("M02"),
                reachableModuleIds = setOf("M02"),
                authorityAdmissible = true,
                isMembershipAuthority = true
            )
        )
        assertTrue(decisions.isEmpty())
    }
}
