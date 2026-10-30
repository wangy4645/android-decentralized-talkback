package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPP Cases 1–6. Does not wire Coordinator / UI / Recovery.
 */
class ConferencePresenceProjectorCppContractTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")
    private val roster3 = listOf("M01", "M02", "M03")
    private val now = 100_000L

    private fun snap(
        media: Map<String, CppMediaRelation>,
        conferenceId: String = "c1",
        producer: String = "M01",
        rosterEpoch: Long = 1,
        anchorEpoch: Long = 10,
        meshGeneration: Long = 1,
        producedAtMs: Long = now
    ) = ConferencePresenceSnapshot(
        conferenceId = conferenceId,
        producerModuleId = producer,
        rosterEpoch = rosterEpoch,
        anchorEpoch = anchorEpoch,
        meshGeneration = meshGeneration,
        producedAtMs = producedAtMs,
        mediaByModuleId = media
    )

    private fun viaAnchor(vararg ids: String) =
        ids.associateWith { CppMediaRelation.VIA_ANCHOR }

    @Test
    fun case1_roster4_presence4_participants4() {
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 10,
            snapshot = snap(viaAnchor("M01", "M02", "M03", "M04")),
            nowMs = now
        )
        assertEquals(4, out.participants.size)
        assertEquals(4, out.joinedCount)
        assertEquals(4, out.connectedCount)
        assertEquals(0, out.joiningCount)
        assertEquals(4, out.uiParticipantCount)
        assertEquals(out.joinedCount, out.participants.count { it.membership == CppMembership.JOINED })
        assertEquals(out.connectedCount, out.participants.count { it.mediaConnected })
        assertEquals(out.joiningCount, out.joinedCount - out.connectedCount)
    }

    @Test
    fun case2_roster4_presence3_keepsMissingMemberUnknown() {
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 10,
            snapshot = snap(viaAnchor("M01", "M02", "M03")),
            nowMs = now
        )
        assertEquals(4, out.participants.size)
        assertEquals(4, out.joinedCount)
        val m04 = out.participants.single { it.moduleId == "M04" }
        assertEquals(CppMembership.JOINED, m04.membership)
        assertEquals(CppMediaRelation.NONE, m04.mediaRelation)
        assertEquals(CppEvidence.UNKNOWN, m04.evidence)
        assertFalse(m04.mediaConnected)
        assertEquals(3, out.connectedCount)
        assertEquals(1, out.joiningCount)
    }

    @Test
    fun case3_roster3_presence4_ignoresNonMember() {
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster3,
            currentAnchorEpoch = 10,
            snapshot = snap(viaAnchor("M01", "M02", "M03", "M04")),
            nowMs = now
        )
        assertEquals(3, out.participants.size)
        assertTrue(out.participants.none { it.moduleId == "M04" })
        assertEquals(3, out.joinedCount)
        assertEquals(3, out.connectedCount)
    }

    @Test
    fun case4_hostNotAnchor_singleCanonicalProjection() {
        val host = "M02"
        val anchor = "M01"
        assertNotEquals(host, anchor)
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 10,
            snapshot = snap(viaAnchor("M01", "M02", "M03", "M04"), producer = anchor),
            nowMs = now
        )
        assertEquals(anchor, snap(viaAnchor("M01"), producer = anchor).producerModuleId)
        assertEquals(4, out.joinedCount)
        assertEquals(4, out.connectedCount)
        assertEquals(out.uiParticipantCount, out.participants.size)
        assertEquals(out.joiningCount, out.joinedCount - out.connectedCount)
    }

    @Test
    fun case5_staleSnapshot_membershipRetained_neverOffline() {
        val produced = now - 10_000L
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 10,
            snapshot = snap(viaAnchor("M01", "M02", "M03", "M04"), producedAtMs = produced),
            nowMs = now,
            staleAfterMs = 5_000L
        )
        assertEquals(4, out.participants.size)
        assertEquals(4, out.joinedCount)
        assertTrue(out.participants.all { it.membership == CppMembership.JOINED })
        assertTrue(out.participants.all { it.evidence == CppEvidence.STALE })
        assertEquals(0, out.connectedCount)
        assertFalse(out.participants.any { it.membership == CppMembership.NOT_JOINED })
    }

    @Test
    fun case5_missingSnapshot_unknownNotAbsent() {
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 10,
            snapshot = null,
            nowMs = now
        )
        assertEquals(4, out.participants.size)
        assertEquals(4, out.joinedCount)
        assertTrue(out.participants.all { it.evidence == CppEvidence.UNKNOWN })
        assertTrue(out.participants.all { it.mediaRelation == CppMediaRelation.NONE })
        assertEquals(0, out.connectedCount)
    }

    @Test
    fun case5_oldAnchorEpoch_rejectedAsMissing() {
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 11,
            snapshot = snap(viaAnchor("M01", "M02", "M03", "M04"), anchorEpoch = 10),
            nowMs = now
        )
        assertEquals(4, out.joinedCount)
        assertTrue(out.participants.all { it.evidence == CppEvidence.UNKNOWN })
        assertEquals(0, out.connectedCount)
    }

    @Test
    fun case6_sameAnchorEpoch_newerMeshGenerationWins() {
        val old = snap(viaAnchor("M01", "M02"), meshGeneration = 1, producedAtMs = now - 100)
        val newer = snap(viaAnchor("M01", "M02", "M03", "M04"), meshGeneration = 2, producedAtMs = now - 50)
        val chosen = ConferencePresenceProjector.selectPresenceSnapshot(
            conferenceId = "c1",
            currentAnchorEpoch = 10,
            candidates = listOf(old, newer)
        )
        assertEquals(2L, chosen?.meshGeneration)
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 10,
            snapshot = chosen,
            nowMs = now
        )
        assertEquals(4, out.connectedCount)
    }

    @Test
    fun case6_staleProducerCannotOverwrite() {
        val staleProducer = snap(
            viaAnchor("M01", "M02", "M03", "M04"),
            anchorEpoch = 9,
            meshGeneration = 99,
            producedAtMs = now
        )
        val current = snap(viaAnchor("M01", "M02"), anchorEpoch = 10, meshGeneration = 1)
        val chosen = ConferencePresenceProjector.selectPresenceSnapshot(
            conferenceId = "c1",
            currentAnchorEpoch = 10,
            candidates = listOf(staleProducer, current)
        )
        assertEquals(10L, chosen?.anchorEpoch)
        assertEquals(1L, chosen?.meshGeneration)
        val out = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 10,
            snapshot = chosen,
            nowMs = now
        )
        assertEquals(2, out.connectedCount)
        assertEquals(4, out.joinedCount)
    }
}
