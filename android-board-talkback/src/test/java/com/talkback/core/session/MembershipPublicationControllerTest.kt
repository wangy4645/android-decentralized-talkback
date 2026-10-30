package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MembershipPublicationControllerTest {

    @Before
    fun silenceLogs() {
        MembershipPublicationController.producerLogger = {}
        GroupMembershipSupport.rosterInvariantLogger = {}
        GroupMembershipSupport.rosterMutationLogger = {}
    }

    @After
    fun resetLogs() {
        MembershipPublicationController.producerLogger = null
        GroupMembershipSupport.rosterInvariantLogger = null
        GroupMembershipSupport.rosterMutationLogger = null
    }

    private fun session(vararg memberIds: String, accepted: Boolean = true): TalkbackSession {
        val local = ModuleId("M02")
        val s = TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = EndpointAddress(local, EndpointId("E02")),
            channelId = "CH-01"
        )
        s.accepted = accepted
        s.groupMembers = memberIds.map { EndpointAddress(ModuleId(it), EndpointId("E01")) }
        s.memberModules.addAll(memberIds.map { ModuleId(it) })
        GroupMembershipSupport.syncMembershipFromGroupMembers(s)
        return s
    }

    @Test
    fun evaluate_deferWhenParticipantPromoting() {
        val s = session("M01", "M03")
        s.memberModules.add(ModuleId("M02"))
        val eval = MembershipPublicationController.evaluatePublicationDecision(s, true, "M01")
        assertEquals(MembershipPublicationController.PublicationDecision.DEFER, eval.decision)
        assertEquals(
            MembershipPublicationController.PublicationDeferReason.PARTICIPANT_PROMOTING,
            eval.reason
        )
    }

    @Test
    fun evaluate_ignoreWhenNotAuthority() {
        val s = session("M01", "M02", "M03")
        val eval = MembershipPublicationController.evaluatePublicationDecision(s, false, "M02")
        assertEquals(MembershipPublicationController.PublicationDecision.IGNORE, eval.decision)
    }

    @Test
    fun flush_defersWithoutPublishWhenNotReady() {
        val s = session("M01", "M03")
        s.memberModules.add(ModuleId("M02"))
        MembershipPublicationController.markPublicationPending(s)
        var publishCount = 0
        val result = MembershipPublicationController.flushPublicationIfReady(s, true, "M01") {
            publishCount++
            1
        }
        assertEquals(0, publishCount)
        assertFalse(result.published)
        assertEquals(1L, s.membershipPublicationPendingGeneration)
        assertEquals(0L, s.membershipPublishedGeneration)
    }

    @Test
    fun flush_publishesOnceWhenReady() {
        val s = session("M01", "M02", "M03")
        MembershipPublicationController.markPublicationPending(s)
        var publishCount = 0
        val result = MembershipPublicationController.flushPublicationIfReady(s, true, "M02") {
            publishCount++
            2
        }
        assertEquals(1, publishCount)
        assertTrue(result.published)
        assertEquals(1L, s.membershipPublishedGeneration)
    }

    @Test
    fun mutationStorm_coalescesToSinglePublish() {
        val s = session("M01", "M02", "M03")
        repeat(7) { MembershipPublicationController.markPublicationPending(s) }
        var publishCount = 0
        MembershipPublicationController.flushPublicationIfReady(s, true, "M02") {
            publishCount++
            1
        }
        assertEquals(1, publishCount)
        assertEquals(7L, s.membershipPublishedGeneration)
        MembershipPublicationController.flushPublicationIfReady(s, true, "M02") {
            publishCount++
            1
        }
        assertEquals(1, publishCount)
    }

    @Test
    fun promoteThenFlush_livenessAfterDefer() {
        val local = ModuleId("M01")
        val s = TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = EndpointAddress(local, EndpointId("E01")),
            channelId = "CH-01"
        )
        s.accepted = true
        s.groupMembers = listOf(
            EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        )
        s.memberModules.addAll(listOf(ModuleId("M01"), ModuleId("M03"), ModuleId("M02")))
        GroupMembershipSupport.syncMembershipFromGroupMembers(s)
        MembershipPublicationController.markPublicationPending(s)
        MembershipPublicationController.flushPublicationIfReady(s, true, "M01") { 1 }
        GroupMembershipSupport.promoteToGroupRoster(
            s,
            EndpointAddress(ModuleId("M02"), EndpointId("E02")),
            "M01"
        )
        MembershipPublicationController.markPublicationPending(s)
        var publishCount = 0
        val result = MembershipPublicationController.flushPublicationIfReady(s, true, "M01") {
            publishCount++
            1
        }
        assertEquals(1, publishCount)
        assertTrue(result.published)
        assertEquals(s.membershipPublicationPendingGeneration, s.membershipPublishedGeneration)
    }
}
