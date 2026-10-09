package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceMediaEdgeRealizationContractTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun hostIsAnchor() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M01",
            members = members4
        )
    )

    private fun hostNotAnchor() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M04",
            members = members4
        )
    )

    private fun mesh() = ConferenceTopologySnapshot(
        conferenceId = "c1",
        rosterEpoch = 1L,
        anchorEpoch = 0L,
        anchorId = null,
        meshGeneration = 0L,
        topologyMode = ConferenceTopologyMode.MESH,
        hostModuleId = "M01",
        members = listOf("M01", "M02", "M03"),
        actualMediaEdges = emptySet()
    )

    private fun req(
        local: String,
        remote: String,
        snap: ConferenceTopologySnapshot,
        conferenceId: String = snap.conferenceId,
        meshGeneration: Long = snap.meshGeneration,
        anchorEpoch: Long = snap.anchorEpoch,
        mode: ConferenceTopologyMode = snap.topologyMode
    ) = RealizationAuthRequest(
        conferenceId = conferenceId,
        localModuleId = local,
        remoteModuleId = remote,
        meshGeneration = meshGeneration,
        anchorEpoch = anchorEpoch,
        declaredMode = mode
    )

    @Test
    fun caseA_hostIsAnchor_hostOffersSpokes() {
        val snap = hostIsAnchor()
        assertTrue(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M02", snap))
        assertTrue(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M03", snap))
        assertTrue(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M04", snap))
        assertFalse(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M02", "M03", snap))
        assertFalse(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M02", "M01", snap))
        assertEquals(
            setOf("M02", "M03", "M04"),
            ConferenceMediaEdgeRealizationContract.offerRemotesIfAnchor("M01", snap)
        )
    }

    @Test
    fun caseB1_hostNotAnchor_onlyAnchorOffers() {
        val snap = hostNotAnchor()
        assertTrue(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M04", "M01", snap))
        assertTrue(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M04", "M02", snap))
        assertTrue(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M04", "M03", snap))
        assertFalse(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M02", snap))
        assertFalse(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M03", snap))
        assertFalse(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M04", snap))
    }

    @Test
    fun caseB2_hostNotAnchor_membershipOnlyAllowed() {
        val snap = hostNotAnchor()
        assertFalse(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M02", snap))
        assertTrue(ConferenceMediaEdgeRealizationContract.maySendMembershipInvite("M01", "M02"))
        assertTrue(ConferenceMediaEdgeRealizationContract.maySendMembershipInvite("M01", "M04"))
        assertFalse(ConferenceMediaEdgeRealizationContract.maySendMembershipInvite("M01", "M01"))
    }

    @Test
    fun caseB3_allHostMediaEntries_cannotOfferSpoke() {
        val snap = hostNotAnchor()
        val hostSpoke = req("M01", "M02", snap)
        val hostAsSpokeOnStar = req("M01", "M04", snap)
        val anchorSpoke = req("M04", "M02", snap)
        ConferenceMediaOfferEntry.values().forEach { entry ->
            assertFalse(
                entry.name,
                ConferenceMediaEdgeRealizationContract.mayCreateOfferAtEntry(entry, hostSpoke, snap)
            )
            assertFalse(
                entry.name,
                ConferenceMediaEdgeRealizationContract.mayCreateOfferAtEntry(entry, hostAsSpokeOnStar, snap)
            )
            assertTrue(
                entry.name,
                ConferenceMediaEdgeRealizationContract.mayCreateOfferAtEntry(entry, anchorSpoke, snap)
            )
        }
        // Ghost Host→spoke pair: not in Desired set.
        val ghostDenied = ConferenceMediaEdgeRealizationContract.authorizeCreateOffer(hostSpoke, snap)
        assertEquals(
            ConferenceMediaEdgeRealizationContract.REASON_EDGE_NOT_ADMITTED,
            (ghostDenied as RealizationDecision.Denied).reason
        )
        // Host is on admitted star edge but is not Anchor owner.
        val ownerDenied = ConferenceMediaEdgeRealizationContract.authorizeCreateOffer(hostAsSpokeOnStar, snap)
        assertEquals(
            ConferenceMediaEdgeRealizationContract.REASON_NOT_EDGE_OWNER,
            (ownerDenied as RealizationDecision.Denied).reason
        )
    }

    @Test
    fun caseC_ghostPair_cannotCreateConferencePc() {
        val snap = hostNotAnchor()
        assertFalse(
            ConferenceMediaEdgeRealizationContract.mayCreateConferencePeerConnection("M01", "M02", snap)
        )
        assertFalse(
            ConferenceMediaEdgeRealizationContract.mayCreateConferencePeerConnection("M01", "M03", snap)
        )
        assertTrue(
            ConferenceMediaEdgeRealizationContract.mayCreateConferencePeerConnection("M02", "M04", snap)
        )
    }

    @Test
    fun caseD_mesh_legacyPairwiseAllowed() {
        val snap = mesh()
        assertTrue(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M02", snap))
        assertTrue(
            ConferenceMediaEdgeRealizationContract.mayCreateConferencePeerConnection("M01", "M02", snap)
        )
        assertTrue(
            ConferenceMediaEdgeRealizationContract.mayCreateOffer(
                "M01",
                "M02",
                snapshot = null,
                declaredMode = ConferenceTopologyMode.MESH
            )
        )
    }

    @Test
    fun caseAnchorNullSnapshot_noPairwiseFallback() {
        assertFalse(
            ConferenceMediaEdgeRealizationContract.mayCreateOffer(
                "M01",
                "M02",
                snapshot = null,
                declaredMode = ConferenceTopologyMode.ANCHOR,
                conferenceId = "c1",
                meshGeneration = 1L,
                anchorEpoch = 100L
            )
        )
        val denied = ConferenceMediaEdgeRealizationContract.authorizeCreateOffer(
            RealizationAuthRequest(
                conferenceId = "c1",
                localModuleId = "M04",
                remoteModuleId = "M02",
                meshGeneration = 1L,
                anchorEpoch = 100L,
                declaredMode = ConferenceTopologyMode.ANCHOR
            ),
            current = null
        )
        assertEquals(
            ConferenceMediaEdgeRealizationContract.REASON_SNAPSHOT_REQUIRED,
            (denied as RealizationDecision.Denied).reason
        )
    }

    @Test
    fun caseE1_oldMeshGeneration_rejected() {
        val snap = hostNotAnchor()
        val denied = ConferenceMediaEdgeRealizationContract.authorizeCreateOffer(
            req("M04", "M02", snap, meshGeneration = snap.meshGeneration - 1),
            snap
        )
        assertEquals(
            ConferenceMediaEdgeRealizationContract.REASON_GENERATION_STALE,
            (denied as RealizationDecision.Denied).reason
        )
    }

    @Test
    fun caseE2_oldAnchorEpoch_rejected() {
        val snap = hostNotAnchor()
        val denied = ConferenceMediaEdgeRealizationContract.authorizeCreateOffer(
            req("M04", "M02", snap, anchorEpoch = snap.anchorEpoch - 1),
            snap
        )
        assertEquals(
            ConferenceMediaEdgeRealizationContract.REASON_EPOCH_STALE,
            (denied as RealizationDecision.Denied).reason
        )
    }

    @Test
    fun caseE3_wrongConference_rejected() {
        val snap = hostNotAnchor()
        val denied = ConferenceMediaEdgeRealizationContract.authorizeCreateOffer(
            req("M04", "M02", snap, conferenceId = "other"),
            snap
        )
        assertEquals(
            ConferenceMediaEdgeRealizationContract.REASON_CONFERENCE_MISMATCH,
            (denied as RealizationDecision.Denied).reason
        )
    }

    @Test
    fun caseF_anchorReconnectAllowed_oldHostEdgeCannotRegain() {
        val snap = hostNotAnchor()
        assertTrue(
            ConferenceMediaEdgeRealizationContract.shouldAcceptAnchorOfferOnExistingSession(
                existingSessionId = "c1",
                inviteSessionId = "c1",
                localModuleId = "M02",
                callerModuleId = "M04",
                payloadSdpBlank = false,
                snapshot = snap
            )
        )
        assertFalse(
            ConferenceMediaEdgeRealizationContract.shouldAcceptAnchorOfferOnExistingSession(
                existingSessionId = "c1",
                inviteSessionId = "c1",
                localModuleId = "M02",
                callerModuleId = "M01",
                payloadSdpBlank = false,
                snapshot = snap
            )
        )
        assertFalse(ConferenceMediaEdgeRealizationContract.mayCreateOffer("M01", "M02", snap))
        assertFalse(
            ConferenceMediaEdgeRealizationContract.mayCreateConferencePeerConnection("M01", "M02", snap)
        )
    }
}
