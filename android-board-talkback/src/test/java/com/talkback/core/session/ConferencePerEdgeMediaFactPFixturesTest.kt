package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1–P8 contract fixtures. FactsAdapter / Coordinator NOT AUTHORIZED.
 */
class ConferencePerEdgeMediaFactPFixturesTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")
    private val now = 50_000L
    private val eM04 = MediaEdge("M01", "M04")
    private val eM02 = MediaEdge("M01", "M02")

    private fun star() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M01",
            members = members4,
            meshGeneration = 5L,
            anchorEpoch = 10L
        )
    )

    private fun fact(
        edge: MediaEdge = eM04,
        usable: Boolean,
        producer: String = "M01",
        producedAtMs: Long = now,
        conferenceId: String = "c1",
        anchorEpoch: Long = 10L,
        meshGeneration: Long = 5L
    ) = PerEdgeMediaUsabilityFact(
        conferenceId = conferenceId,
        anchorEpoch = anchorEpoch,
        meshGeneration = meshGeneration,
        producedAtMs = producedAtMs,
        producerModuleId = producer,
        edge = edge,
        usable = usable
    )

    private fun input(
        local: String,
        facts: List<PerEdgeMediaUsabilityFact>,
        snapshot: ConferenceTopologySnapshot = star(),
        iceToAnchor: Boolean = false,
        nowMs: Long = now
    ) = ConferencePerEdgeMediaFactInput(
        snapshot = snapshot,
        localModuleId = local,
        facts = facts,
        nowMs = nowMs,
        localIceToAnchor = iceToAnchor
    )

    private fun avatarOf(remote: String, view: CppStarEdgeView): CppAvatarAvailability {
        val record = ParticipantPresenceRecord(
            moduleId = remote,
            membership = CppMembership.JOINED,
            mediaRelation = view.mediaRelation,
            evidence = view.evidence
        )
        return ConferencePresenceAvatarBind.resolveAvailability(
            record = record,
            isLocal = false,
            localCaptureBlocked = false,
            recovering = view.reconnecting
        )
    }

    @Test
    fun p1_endpointUsable_direct() {
        val snapshot = star()
        val view = ConferencePerEdgeMediaFactContract.viewOf(
            input("M01", listOf(fact(usable = true))),
            "M04"
        )!!
        assertEquals(CppMediaRelation.DIRECT, view.mediaRelation)
        assertTrue(view.confirmedUsable)
        assertEquals(CppAvatarAvailability.NORMAL, avatarOf("M04", view))
        assertEquals(3, snapshot.actualMediaEdges.size)
    }

    @Test
    fun p2_endpointUnusable_joining_admissionKept() {
        val snapshot = star()
        val before = snapshot.actualMediaEdges.toSet()
        val view = ConferencePerEdgeMediaFactContract.viewOf(
            input("M01", listOf(fact(usable = false))),
            "M04"
        )!!
        assertFalse(view.confirmedUsable)
        assertFalse(view.reconnecting)
        assertEquals(CppAvatarAvailability.JOINING, avatarOf("M04", view))
        assertEquals(before, snapshot.actualMediaEdges)
        assertTrue(eM04 in snapshot.actualMediaEdges)
        val targets = RecoveryEdgeProvider.project(snapshot).targets
        assertTrue(targets.all { it.mediaEdge in before })
        assertTrue(targets.any { it.mediaEdge == eM04 })
        assertFalse(targets.any { it.mediaEdge == MediaEdge("M03", "M04") })
    }

    @Test
    fun p3_nonEndpointConsumesUsable_viaAnchor() {
        val view = ConferencePerEdgeMediaFactContract.viewOf(
            input("M03", listOf(fact(usable = true, producer = "M01")), iceToAnchor = true),
            "M04"
        )!!
        assertEquals(CppMediaRelation.VIA_ANCHOR, view.mediaRelation)
        assertTrue(view.confirmedUsable)
        assertEquals(CppAvatarAvailability.NORMAL, avatarOf("M04", view))
        assertFalse(
            ConferencePerEdgeMediaFactContract.isEndpointProducer(eM04, "M03")
        )
    }

    @Test
    fun p4_nonEndpointConsumesUnusable_joining_notPeerPair() {
        val snapshot = star()
        val view = ConferencePerEdgeMediaFactContract.viewOf(
            input("M03", listOf(fact(usable = false)), iceToAnchor = true, snapshot = snapshot),
            "M04"
        )!!
        assertNotEquals(CppMediaRelation.VIA_ANCHOR, view.mediaRelation)
        assertFalse(view.confirmedUsable)
        assertFalse(view.reconnecting)
        assertEquals(CppAvatarAvailability.JOINING, avatarOf("M04", view))
        assertEquals(3, snapshot.actualMediaEdges.size)
        assertFalse(MediaEdge("M03", "M04") in snapshot.actualMediaEdges)
        val targets = RecoveryEdgeProvider.project(snapshot).targets.map { it.mediaEdge }.toSet()
        assertEquals(snapshot.actualMediaEdges, targets)
    }

    @Test
    fun p5_missingFact_failClosed_notViaAnchorNormal() {
        val view = ConferencePerEdgeMediaFactContract.viewOf(
            input("M03", facts = emptyList(), iceToAnchor = true),
            "M04"
        )!!
        assertFalse(view.confirmedUsable)
        assertFalse(view.reconnecting)
        assertNotEquals(CppMediaRelation.VIA_ANCHOR, view.mediaRelation)
        assertEquals(CppAvatarAvailability.JOINING, avatarOf("M04", view))
        assertNotEquals(CppAvatarAvailability.NORMAL, avatarOf("M04", view))
        assertNotEquals(CppAvatarAvailability.RECONNECTING, avatarOf("M04", view))
    }

    @Test
    fun p6_staleAndEpochAndGeneration_failClosed() {
        val snapshot = star()
        val rejected = listOf(
            fact(usable = true, conferenceId = "other"),
            fact(usable = true, anchorEpoch = 9L),
            fact(usable = true, meshGeneration = 4L),
            fact(usable = true, producedAtMs = now - 20_000L)
        )
        for (bad in rejected) {
            val view = ConferencePerEdgeMediaFactContract.viewOf(
                input("M03", listOf(bad), snapshot = snapshot, iceToAnchor = true),
                "M04"
            )!!
            assertFalse("fact=$bad", view.confirmedUsable)
            assertFalse(view.reconnecting)
            assertNotEquals(CppMediaRelation.VIA_ANCHOR, view.mediaRelation)
            assertEquals(CppAvatarAvailability.JOINING, avatarOf("M04", view))
        }
        val nonEndpointProducer = fact(usable = true, producer = "M03")
        val leaked = ConferencePerEdgeMediaFactContract.viewOf(
            input("M03", listOf(nonEndpointProducer), iceToAnchor = true),
            "M04"
        )!!
        assertFalse(leaked.confirmedUsable)
        assertFalse(leaked.reconnecting)
    }

    @Test
    fun p7_mesh_noStarViaAnchorFact() {
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = members4,
            rosterEpoch = 1L,
            meshGeneration = 2L
        )
        assertTrue(mesh.actualMediaEdges.isEmpty())
        val view = ConferencePerEdgeMediaFactContract.viewOf(
            input("M03", listOf(fact(usable = true)), snapshot = mesh, iceToAnchor = true),
            "M04"
        )
        assertNull(view)
    }

    @Test
    fun p8_readOnly_roomLiveMayCoexistWithAvatarJoining() {
        val snapshot = star()
        val before = snapshot.actualMediaEdges.toSet()
        val view = ConferencePerEdgeMediaFactContract.viewOf(
            input("M03", listOf(fact(usable = false)), iceToAnchor = true, snapshot = snapshot),
            "M04"
        )!!
        assertEquals(CppAvatarAvailability.JOINING, avatarOf("M04", view))
        assertEquals(before, snapshot.actualMediaEdges)
        val health = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(eM02, usable = true)
                )
            )
        )
        assertTrue(health.mediaUsable)
        val targets = RecoveryEdgeProvider.project(snapshot).targets
        assertEquals(before, snapshot.actualMediaEdges)
        assertTrue(targets.all { it.mediaEdge in before })
        assertFalse(targets.any { it.mediaEdge == MediaEdge("M03", "M04") })
    }
}
