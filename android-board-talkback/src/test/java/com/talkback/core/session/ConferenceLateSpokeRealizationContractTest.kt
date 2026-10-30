package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceLateSpokeRealizationContractTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun snap(
        conferenceId: String = "C1",
        meshGeneration: Long = 1L,
        anchorEpoch: Long = 100L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = conferenceId,
            hostModuleId = "M01",
            anchorId = "M03",
            members = members4,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    private fun req(
        local: String,
        remote: String,
        snap: ConferenceTopologySnapshot,
        conferenceId: String = snap.conferenceId,
        meshGeneration: Long = snap.meshGeneration,
        anchorEpoch: Long = snap.anchorEpoch
    ) = RealizationAuthRequest(
        conferenceId = conferenceId,
        localModuleId = local,
        remoteModuleId = remote,
        meshGeneration = meshGeneration,
        anchorEpoch = anchorEpoch,
        declaredMode = snap.topologyMode
    )

    private fun maybeDispatch(
        local: String,
        remote: String,
        phase: ConferenceLateSpokeEdgePhase,
        snap: ConferenceTopologySnapshot,
        conferenceId: String = snap.conferenceId,
        meshGeneration: Long = snap.meshGeneration,
        anchorEpoch: Long = snap.anchorEpoch
    ): Boolean =
        ConferenceLateSpokeRealizationContract.decide(
            req(local, remote, snap, conferenceId, meshGeneration, anchorEpoch),
            snap,
            phase
        ) is RealizationDecision.Authorized

    @Test
    fun lateAccept_anchorDispatchesExactlyOnceForUnrealizedSpoke() {
        val snap = snap()
        val dispatches = mutableListOf<String>()
        fun accept(local: String, remote: String, phase: ConferenceLateSpokeEdgePhase) {
            if (maybeDispatch(local, remote, phase, snap)) {
                dispatches += "$local->$remote"
            }
        }

        accept("M03", "M01", ConferenceLateSpokeEdgePhase.CONNECTED)
        accept("M03", "M02", ConferenceLateSpokeEdgePhase.CONNECTED)
        accept("M03", "M04", ConferenceLateSpokeEdgePhase.NONE)
        accept("M01", "M04", ConferenceLateSpokeEdgePhase.NONE)
        accept("M01", "M02", ConferenceLateSpokeEdgePhase.NONE)
        val current = snap()
        if (maybeDispatch("M03", "M04", ConferenceLateSpokeEdgePhase.NONE, current, meshGeneration = 0L)) {
            dispatches += "stale"
        }
        accept("M03", "M04", ConferenceLateSpokeEdgePhase.OFFERING)

        assertEquals(listOf("M03->M04"), dispatches)
    }

    @Test
    fun lateAcceptTwice_realizationCountIsOne() {
        val snap = snap()
        var count = 0
        var phase = ConferenceLateSpokeEdgePhase.NONE
        repeat(2) {
            if (maybeDispatch("M03", "M04", phase, snap)) {
                count++
                phase = ConferenceLateSpokeEdgePhase.OFFERING
            }
        }
        assertEquals(1, count)
    }

    @Test
    fun hostDoesNotRealizeSpokeEdge_butForwardsToAnchor() {
        val snap = snap()
        val hostDecision = ConferenceLateSpokeRealizationContract.decide(
            req("M01", "M04", snap),
            snap,
            ConferenceLateSpokeEdgePhase.NONE
        )
        assertTrue(hostDecision is RealizationDecision.Denied)
        assertEquals(
            ConferenceMediaEdgeRealizationContract.REASON_EDGE_NOT_ADMITTED,
            (hostDecision as RealizationDecision.Denied).reason
        )
        assertTrue(
            ConferenceLateSpokeRealizationContract.shouldForwardMembershipToAnchor("M01", "M04", snap)
        )
        assertFalse(
            ConferenceLateSpokeRealizationContract.shouldForwardMembershipToAnchor("M03", "M04", snap)
        )
        assertFalse(maybeDispatch("M01", "M02", ConferenceLateSpokeEdgePhase.NONE, snap))
    }

    @Test
    fun oldGeneration_zeroRealization() {
        val current = snap(meshGeneration = 1L, anchorEpoch = 100L)
        val denied = ConferenceLateSpokeRealizationContract.decide(
            req("M03", "M04", current, meshGeneration = 0L, anchorEpoch = 100L),
            current,
            ConferenceLateSpokeEdgePhase.NONE
        )
        assertEquals(
            ConferenceMediaEdgeRealizationContract.REASON_GENERATION_STALE,
            (denied as RealizationDecision.Denied).reason
        )
    }
}
