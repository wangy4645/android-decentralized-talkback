package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.TopologyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #190: shared membership digest for HELLO + conference recovery convergence. */
class MembershipDigestSupportTest {

    private val channelId = "CH-01"
    private val roster = listOf("M01", "M02", "M03")
    private val authorityHash188 = -528664596

    private fun conferenceSession(
        id: String = "cnf-1",
        rosterEpoch: Long = 1L,
        groupMembers: List<EndpointAddress> = emptyList()
    ): TalkbackSession {
        val local = EndpointAddress(ModuleId("M02"), EndpointId("E02"))
        val session = TalkbackSession(id, SessionType.CONFERENCE, local, channelId)
        session.rosterEpoch = rosterEpoch
        session.groupMembers = groupMembers
        session.mediaTopology = GroupMediaTopology.ANCHOR
        session.accepted = true
        return session
    }

    private fun groupSession(
        id: String = "grp:CH-01",
        rosterEpoch: Long = 1L,
        members: List<String> = roster
    ): TalkbackSession {
        val local = EndpointAddress(ModuleId("M02"), EndpointId("E02"))
        val session = TalkbackSession(id, SessionType.GROUP, local, channelId)
        session.rosterEpoch = rosterEpoch
        session.groupMembers = members.map { EndpointAddress(ModuleId(it), EndpointId("E01")) }
        GroupMembershipSupport.syncMembershipFromGroupMembers(session)
        session.accepted = true
        return session
    }

    @Test
    fun field188_meshRosterDigest_matchesAuthorityHelloHash() {
        val digest = MembershipDigestSupport.digestFromConferenceRoster(
            conferenceSession(),
            roster
        )
        assertEquals(1L, digest.rosterEpoch)
        assertEquals(authorityHash188, digest.memberHash)
    }

    @Test
    fun conferenceOnly_helloAndConvergenceUseSameDigestFormula() {
        val conference = conferenceSession()
        val helloSession = MembershipDigestSupport.selectHelloDigestSession(listOf(conference))!!
        val helloDigest = MembershipDigestSupport.digestFromConferenceRoster(conference, roster)
        val convergenceDigest = MembershipDigestSupport.convergenceLocalDigest(
            conferenceSession = conference,
            conferenceRosterModuleIds = roster
        )
        assertEquals(SessionType.CONFERENCE, helloSession.type)
        assertEquals(helloDigest, convergenceDigest)
    }

    @Test
    fun helloDigestSession_prefersConferenceOverCoexistingGroup() {
        val conference = conferenceSession()
        val group = groupSession(rosterEpoch = 3L, members = listOf("M01", "M02"))
        val selected = MembershipDigestSupport.selectHelloDigestSession(listOf(group, conference))
        assertEquals(SessionType.CONFERENCE, selected!!.type)
        assertEquals("cnf-1", selected.id)
    }

    @Test
    fun groupOnChannel_conferenceRecoveryUsesConferenceDigestNotGroupEpoch() {
        val conference = conferenceSession(
            rosterEpoch = 1L,
            groupMembers = listOf(EndpointAddress(ModuleId("M02"), EndpointId("E02")))
        )
        val group = groupSession(rosterEpoch = 3L, members = listOf("M01", "M02"))
        val digest = MembershipDigestSupport.convergenceLocalDigest(
            conferenceSession = conference,
            conferenceRosterModuleIds = roster
        )
        assertEquals(1L, digest.rosterEpoch)
        assertEquals(authorityHash188, digest.memberHash)
        assertFalse(digest.rosterEpoch == group.rosterEpoch)
    }

    @Test
    fun coexistence_alignsWhenAuthorityUsesConferenceDigestDespiteStaleGroup() {
        val conference = conferenceSession(rosterEpoch = 1L)
        val group = groupSession(rosterEpoch = 3L, members = listOf("M01", "M02"))
        val authority = MembershipDigestSupport.digestFromConferenceRoster(conference, roster)
        val local = MembershipDigestSupport.convergenceLocalDigest(conference, roster)
        assertEquals(authority, local)
        val outcome = DefaultMembershipAuthorityResolver { readChannelId ->
            if (readChannelId == channelId) authority else null
        }.evaluateMembershipConvergence(
            RecoveryMembershipContext(
                channelId = channelId,
                conferenceSessionId = conference.id,
                localMembershipView = local,
                localMembershipSessionId = conference.id
            ),
            localGroupSessionId = conference.id
        )
        assertTrue(outcome.converged)
        assertEquals("ALIGNED", outcome.reason)
        assertFalse(local.rosterEpoch == group.rosterEpoch)
    }

    @Test
    fun groupOnly_helloDigestSessionUsesGroup() {
        val group = groupSession()
        val selected = MembershipDigestSupport.selectHelloDigestSession(listOf(group))!!
        assertEquals(SessionType.GROUP, selected.type)
        assertEquals("grp:CH-01", selected.id)
    }

    @Test
    fun sameRoster_sameDigest_differentEpoch_mismatch() {
        val aligned = MembershipDigestSupport.digest(channelId, 1L, 0L, 0L, roster)
        val sameRoster = MembershipDigestSupport.digest(channelId, 1L, 0L, 0L, roster.reversed())
        val differentEpoch = MembershipDigestSupport.digest(channelId, 2L, 0L, 0L, roster)
        assertEquals(aligned, sameRoster)
        assertFalse(aligned.rosterEpoch == differentEpoch.rosterEpoch)
        assertFalse(aligned.memberHash == differentEpoch.memberHash)
    }

    @Test
    fun directMeeting_emptyGroupMembers_meshRosterMatchesAuthority() {
        val conference = conferenceSession(groupMembers = emptyList())
        val wrong = TopologyDigest.fromSession(conference)
        val fixed = MembershipDigestSupport.digestFromConferenceRoster(conference, roster)
        assertEquals(authorityHash188, fixed.memberHash)
        assertFalse(wrong.memberHash == fixed.memberHash)
    }

    @Test
    fun resolver_convergedWhenMeshRosterDigestMatchesAuthority() {
        val authority = MembershipDigestSupport.digest(channelId, 1L, 0L, 0L, roster)
        val local = MembershipDigestSupport.digestFromConferenceRoster(conferenceSession(), roster)
        val resolver = DefaultMembershipAuthorityResolver { readChannelId ->
            if (readChannelId == channelId) authority else null
        }
        val outcome = resolver.evaluateMembershipConvergence(
            RecoveryMembershipContext(
                channelId = channelId,
                conferenceSessionId = "cnf-1",
                localMembershipView = local,
                localMembershipSessionId = "cnf-1"
            ),
            localGroupSessionId = null
        )
        assertTrue(outcome.converged)
        assertEquals("ALIGNED", outcome.reason)
    }

    @Test
    fun resolver_stillBlocksWhenAuthorityHashMismatch() {
        val authority = MembershipDigestSupport.digest(channelId, 1L, 0L, 0L, roster)
        val local = MembershipDigestSupport.digest(channelId, 1L, 0L, 0L, listOf("M01", "M02"))
        val outcome = DefaultMembershipAuthorityResolver { channelId -> authority }
            .evaluateMembershipConvergence(
                RecoveryMembershipContext(
                    channelId = channelId,
                    conferenceSessionId = "cnf-1",
                    localMembershipView = local
                ),
                localGroupSessionId = null
            )
        assertFalse(outcome.converged)
        assertEquals("HASH_MISMATCH", outcome.reason)
    }
}
