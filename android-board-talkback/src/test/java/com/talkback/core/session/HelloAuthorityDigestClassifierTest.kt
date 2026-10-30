package com.talkback.core.session

import com.talkback.core.model.AuthorityDigestObservation
import com.talkback.core.model.AuthorityDigestSource
import com.talkback.core.model.ChannelAuthorityDigestStore
import com.talkback.core.model.MembershipAuthorityPlane
import com.talkback.core.model.TopologyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloAuthorityDigestClassifierTest {

    private val conferenceDigest = TopologyDigest(rosterEpoch = 5L, anchorEpoch = 100L, memberHash = 1783685497)
    private val groupDigest = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 0L, memberHash = -289621531)
    private val conference = HelloAuthorityDigestClassifier.SessionDigest("cnf-live", conferenceDigest)
    private val group = HelloAuthorityDigestClassifier.SessionDigest("grp:CH-01", groupDigest)

    @Test
    fun inMeetingGroupHello_doesNotObserveConference() {
        val decision = HelloAuthorityDigestClassifier.classify(
            incoming = groupDigest,
            conference = conference,
            group = group
        )
        assertTrue(decision.observe)
        assertEquals(MembershipAuthorityPlane.GROUP, decision.plane)
        assertEquals("HELLO_MATCHES_GROUP", decision.reason)

        val store = ChannelAuthorityDigestStore()
        store.put(observation(MembershipAuthorityPlane.CONFERENCE, conferenceDigest, "cnf-live"))
        store.put(observation(MembershipAuthorityPlane.GROUP, groupDigest, "grp:CH-01"))
        assertEquals(5L, store.get("CH-01", MembershipAuthorityPlane.CONFERENCE)!!.digest.rosterEpoch)
        assertEquals(1L, store.get("CH-01", MembershipAuthorityPlane.GROUP)!!.digest.rosterEpoch)
    }

    @Test
    fun bothPlanesLive_unclassifiedHello_ignored() {
        val other = TopologyDigest(rosterEpoch = 9L, anchorEpoch = 0L, memberHash = 42)
        val decision = HelloAuthorityDigestClassifier.classify(
            incoming = other,
            conference = conference,
            group = group
        )
        assertFalse(decision.observe)
        assertEquals("UNCLASSIFIED_HELLO_WHILE_BOTH_PLANES_LIVE", decision.reason)
    }

    @Test
    fun afterMeetingEnd_groupHelloObservesGroup() {
        val decision = HelloAuthorityDigestClassifier.classify(
            incoming = groupDigest,
            conference = null,
            group = group
        )
        assertTrue(decision.observe)
        assertEquals(MembershipAuthorityPlane.GROUP, decision.plane)
        assertEquals("HELLO_MATCHES_GROUP", decision.reason)
    }

    @Test
    fun afterMeetingEnd_unmatchedGroupHelloStillObservesGroup() {
        val newerGroup = TopologyDigest(rosterEpoch = 2L, anchorEpoch = 0L, memberHash = 99)
        val decision = HelloAuthorityDigestClassifier.classify(
            incoming = newerGroup,
            conference = null,
            group = group
        )
        assertTrue(decision.observe)
        assertEquals(MembershipAuthorityPlane.GROUP, decision.plane)
        assertEquals("HELLO_GROUP_ONLY_CONTEXT", decision.reason)
    }

    @Test
    fun authorizedMeetingEnd_clearsConferencePlane_groupSlotRemains() {
        val store = ChannelAuthorityDigestStore()
        store.put(observation(MembershipAuthorityPlane.CONFERENCE, conferenceDigest, "cnf-live"))
        store.put(observation(MembershipAuthorityPlane.GROUP, groupDigest, "grp:CH-01"))
        store.clear("CH-01", MembershipAuthorityPlane.CONFERENCE)
        assertNull(store.get("CH-01", MembershipAuthorityPlane.CONFERENCE))
        assertEquals(1L, store.get("CH-01", MembershipAuthorityPlane.GROUP)!!.digest.rosterEpoch)
    }

    @Test
    fun newConference_replacesOldConferenceFamily_withoutReadingGroupEpoch() {
        val store = ChannelAuthorityDigestStore()
        store.put(observation(MembershipAuthorityPlane.CONFERENCE, conferenceDigest, "cnf-old"))
        store.put(observation(MembershipAuthorityPlane.GROUP, groupDigest, "grp:CH-01"))
        val nextConference = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = 7)
        store.put(observation(MembershipAuthorityPlane.CONFERENCE, nextConference, "cnf-new"))
        val live = store.get("CH-01", MembershipAuthorityPlane.CONFERENCE)!!
        assertEquals("cnf-new", live.sessionFamilyId)
        assertEquals(1L, live.digest.rosterEpoch)
        assertEquals(7, live.digest.memberHash)
        assertEquals(1L, store.get("CH-01", MembershipAuthorityPlane.GROUP)!!.digest.rosterEpoch)
        assertEquals(-289621531, store.get("CH-01", MembershipAuthorityPlane.GROUP)!!.digest.memberHash)
    }

    private fun observation(
        plane: MembershipAuthorityPlane,
        digest: TopologyDigest,
        family: String
    ): AuthorityDigestObservation = AuthorityDigestObservation(
        channelId = "CH-01",
        authorityId = "M01",
        digest = digest,
        observedAtMs = 1L,
        source = AuthorityDigestSource.HELLO,
        membershipContext = plane.name,
        plane = plane,
        sessionFamilyId = family
    )
}
