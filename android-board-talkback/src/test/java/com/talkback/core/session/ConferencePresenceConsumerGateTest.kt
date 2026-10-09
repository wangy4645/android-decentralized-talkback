package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPP Projection Consumer Gate: snapshot counts == compose() vector.
 */
class ConferencePresenceConsumerGateTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")
    private val now = 50_000L

    private fun composeToSnapshot(
        roster: List<String>,
        iceConnected: Set<String>,
        host: String,
        anchor: String,
        producedAtMs: Long = now,
        nowMs: Long = now,
        anchorEpoch: Long = 10L
    ): Pair<ParticipantPresenceProjection, ConferencePresenceProjection> {
        val snap = ConferencePresenceFactsAdapter.snapshotFromLocalObservation(
            conferenceId = "c1",
            producerModuleId = anchor,
            rosterEpoch = 1,
            anchorEpoch = anchorEpoch,
            meshGeneration = 1,
            producedAtMs = producedAtMs,
            localModuleId = host,
            iceConnectedRemoteIds = iceConnected
        )
        val composed = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster,
            currentAnchorEpoch = anchorEpoch,
            snapshot = snap,
            nowMs = nowMs
        )
        return composed to composed.toConferencePresenceProjection()
    }

    private fun assertSameAggregates(
        composed: ParticipantPresenceProjection,
        snapshot: ConferencePresenceProjection
    ) {
        assertEquals(composed.joinedCount, snapshot.joinedCount)
        assertEquals(composed.connectedCount, snapshot.connectedCount)
        assertEquals(composed.joiningCount, snapshot.joiningCount)
        assertEquals(composed.recoveringPeers, snapshot.recoveringPeers)
        assertEquals(composed.participants, snapshot.participants)
        assertEquals(snapshot.joinedCount, snapshot.participants.count { it.membership == CppMembership.JOINED })
        assertEquals(snapshot.connectedCount, snapshot.participants.count { it.mediaConnected })
        assertEquals(snapshot.joiningCount, snapshot.joinedCount - snapshot.connectedCount)
    }

    @Test
    fun consumer_hostNotAnchor_singleProjection() {
        val host = "M02"
        val anchor = "M01"
        assertNotEquals(host, anchor)
        val (composed, snapshot) = composeToSnapshot(
            roster = roster4,
            iceConnected = setOf("M01", "M03"),
            host = host,
            anchor = anchor
        )
        assertSameAggregates(composed, snapshot)
        assertEquals(4, snapshot.joinedCount)
        assertEquals(3, snapshot.connectedCount)
        assertEquals(1, snapshot.joiningCount)
        assertEquals("M04 joining...", ConferencePresenceUiBind.joiningHint(snapshot, false))
    }

    @Test
    fun consumer_roster4_presence3_keepsMember() {
        val (composed, snapshot) = composeToSnapshot(
            roster = roster4,
            iceConnected = setOf("M02", "M03"),
            host = "M01",
            anchor = "M01"
        )
        assertSameAggregates(composed, snapshot)
        assertEquals(4, snapshot.participants.size)
        assertEquals(4, snapshot.joinedCount)
        val m04 = snapshot.participants.single { it.moduleId == "M04" }
        assertEquals(CppEvidence.UNKNOWN, m04.evidence)
        assertEquals(CppMediaRelation.NONE, m04.mediaRelation)
    }

    @Test
    fun consumer_presenceLog_includesVector() {
        val (_, snapshot) = composeToSnapshot(
            roster = roster4,
            iceConnected = setOf("M02", "M03"),
            host = "M01",
            anchor = "M01"
        )
        val line = ConferencePresenceProjectionLog.format(
            conferenceId = "c1",
            localModuleId = "M01",
            producerModuleId = "M01",
            rosterEpoch = 1,
            anchorEpoch = 10,
            meshGeneration = 1,
            projection = snapshot
        )
        assertTrue(line.startsWith("CONFERENCE_PRESENCE_PROJECTION "))
        assertTrue(line.contains("joined=4"))
        assertTrue(line.contains("connected=3"))
        assertTrue(line.contains("joining=1"))
        assertTrue(line.contains("M04:JOINED:NONE:UNKNOWN"))
    }

    @Test
    fun consumer_micHint_doesNotRecountJoining() {
        val (_, snapshot) = composeToSnapshot(
            roster = roster4,
            iceConnected = setOf("M02"),
            host = "M01",
            anchor = "M01"
        )
        assertEquals("Microphone unavailable", ConferencePresenceUiBind.joiningHint(snapshot, true))
        assertEquals(2, snapshot.joiningCount)
        assertEquals("2 joining...", ConferencePresenceUiBind.joiningHint(snapshot, false))
    }

    @Test
    fun consumer_stalePresence_membershipRetained() {
        val (composed, snapshot) = composeToSnapshot(
            roster = roster4,
            iceConnected = setOf("M02", "M03", "M04"),
            host = "M01",
            anchor = "M01",
            producedAtMs = now - 20_000L,
            nowMs = now
        )
        assertSameAggregates(composed, snapshot)
        assertEquals(4, snapshot.joinedCount)
        assertEquals(0, snapshot.connectedCount)
        assertTrue(snapshot.participants.all { it.evidence == CppEvidence.STALE })
        assertEquals("4 joining...", ConferencePresenceUiBind.joiningHint(snapshot, false))
    }
}
