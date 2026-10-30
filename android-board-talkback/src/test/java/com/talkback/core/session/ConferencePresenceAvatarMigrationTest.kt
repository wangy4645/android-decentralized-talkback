package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Avatar consumer migration gate — CPP participants[] only.
 */
class ConferencePresenceAvatarMigrationTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")
    private val now = 50_000L

    private fun projection(
        roster: List<String>,
        iceConnected: Set<String>,
        host: String,
        anchor: String,
        producedAtMs: Long = now,
        recovering: Set<String> = emptySet()
    ): ConferencePresenceProjection {
        val snap = ConferencePresenceFactsAdapter.snapshotFromLocalObservation(
            conferenceId = "c1",
            producerModuleId = anchor,
            rosterEpoch = 1,
            anchorEpoch = 10,
            meshGeneration = 1,
            producedAtMs = producedAtMs,
            localModuleId = host,
            iceConnectedRemoteIds = iceConnected
        )
        return ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster,
            currentAnchorEpoch = 10,
            snapshot = snap,
            nowMs = now,
            recoveringModuleIds = recovering
        ).toConferencePresenceProjection()
    }

    @Test
    fun avatar_listFromParticipants_onlyJoined() {
        val p = projection(roster4, setOf("M02", "M03"), host = "M01", anchor = "M01")
        val rows = ConferencePresenceUiBind.avatarRows(p, "M01", null, false)
        assertEquals(p.participants.count { it.membership == CppMembership.JOINED }, rows.size)
        assertEquals(setOf("M01", "M02", "M03", "M04"), rows.map { it.moduleId }.toSet())
    }

    @Test
    fun avatar_countMatchesProjectionParticipantCount() {
        val p = projection(roster4, setOf("M02", "M03", "M04"), host = "M01", anchor = "M01")
        val rows = ConferencePresenceUiBind.avatarRows(p, "M01", null, false)
        assertEquals(p.participants.size, rows.size)
        assertEquals(p.joinedCount, rows.size)
    }

    @Test
    fun avatar_joinedNoneUnknown_notOffline() {
        val p = projection(roster4, setOf("M02", "M03"), host = "M01", anchor = "M01")
        val m04 = ConferencePresenceUiBind.avatarRows(p, "M01", null, false)
            .single { it.moduleId == "M04" }
        assertEquals(CppAvatarAvailability.JOINING, m04.availability)
        assertNotEquals(CppAvatarAvailability.NORMAL, m04.availability)
    }

    @Test
    fun avatar_viaAnchorFresh_isNormal() {
        val p = projection(roster4, setOf("M01", "M02", "M03", "M04"), host = "M01", anchor = "M01")
        val m02 = ConferencePresenceUiBind.avatarRows(p, "M01", null, false)
            .single { it.moduleId == "M02" }
        assertEquals(CppAvatarAvailability.NORMAL, m02.availability)
    }

    @Test
    fun avatar_aggregateAndRowsShareSameProjection() {
        val p = projection(roster4, setOf("M02", "M03"), host = "M01", anchor = "M01")
        val rows = ConferencePresenceUiBind.avatarRows(p, "M01", null, false)
        val joiningRows = rows.count { it.availability == CppAvatarAvailability.JOINING }
        assertEquals(p.joiningCount, joiningRows)
        assertEquals("M04 joining...", ConferencePresenceUiBind.joiningHint(p, false))
    }

    @Test
    fun avatar_hostNotAnchor_singleList() {
        val p = projection(
            roster4,
            setOf("M01", "M02", "M03", "M04"),
            host = "M02",
            anchor = "M01"
        )
        val rows = ConferencePresenceUiBind.avatarRows(p, "M02", null, false)
        assertEquals(4, rows.size)
        assertEquals(p.joinedCount, rows.size)
    }

    @Test
    fun avatar_joined4Connected3_legalJoiningSemantics() {
        val p = projection(roster4, setOf("M02", "M03"), host = "M01", anchor = "M01")
        assertEquals(4, p.joinedCount)
        assertEquals(3, p.connectedCount)
        assertEquals(1, p.joiningCount)
        val rows = ConferencePresenceUiBind.avatarRows(p, "M01", null, false)
        assertEquals(1, rows.count { it.availability == CppAvatarAvailability.JOINING })
        assertFalse(rows.any { it.moduleId == "M04" && it.availability == CppAvatarAvailability.NORMAL })
    }

    @Test
    fun avatar_staleMembershipRetained_notDropped() {
        val p = projection(
            roster = roster4,
            iceConnected = setOf("M02", "M03", "M04"),
            host = "M01",
            anchor = "M01",
            producedAtMs = now - 20_000L
        )
        val rows = ConferencePresenceUiBind.avatarRows(p, "M01", null, false)
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.availability != CppAvatarAvailability.NORMAL || it.moduleId == "M01" })
    }
}

