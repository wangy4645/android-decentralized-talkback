package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Phase 2-3: Provider eligibility consumed by Controller. R28 unchanged when
 * topologyTargets is null (C7). Controller does not call RecoveryEdgeProvider.
 */
class ConferenceRecoveryBindingPhase23IntegrationTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val sessionId = "c1"
    private val members4 = listOf("M01", "M02", "M03", "M04")
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        controller = ConferenceEdgeRecoveryController(
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 500L,
            clock = { 0L },
            scheduler = scheduler,
            onLog = {},
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true },
            isIceConnected = { _, _ -> false },
            isReceivePathLive = { _, _ -> false },
            canDispatchRecoveryMediaAction = { _, _ -> true }
        )
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun iceFailed(
        remote: String,
        targets: RecoveryTargetSnapshot?,
        initiatesReattach: Boolean = false
    ) {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = remote,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false,
            topologyTargets = targets
        )
    }

    private fun anchorSnapshot(
        meshGeneration: Long = 31L,
        anchorEpoch: Long = 10L,
        anchor: String = "M01"
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = sessionId,
            hostModuleId = "M01",
            anchorId = anchor,
            members = members4,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    @Test
    fun c1_admittedCurrentGenEdge_controllerOpensObligation() {
        val targets = RecoveryEdgeProvider.project(anchorSnapshot())
        iceFailed("M02", targets)
        assertTrue(controller.factsForSession(sessionId).anyRecovering)
        val key = ConferenceRecoveryBindingContract.bindConferenceEdgeKey(
            sessionId,
            "M01",
            targets.targets.first { it.mediaEdge == MediaEdge("M01", "M02") }
        )
        assertTrue(controller.edgeObligationOpen(sessionId, key.remoteModuleId))
    }

    @Test
    fun c2_rosterOnlyPeerPair_controllerDoesNotOpenObligation() {
        val snapshot = anchorSnapshot()
        val targets = RecoveryEdgeProvider.project(snapshot)
        assertTrue("M02" in snapshot.members && "M03" in snapshot.members)
        assertFalse(ConferenceRecoveryBindingContract.admitsMediaEdge(targets, sessionId, MediaEdge("M02", "M03")))
        iceFailed("MX", targets)
        assertFalse(controller.factsForSession(sessionId).anyRecovering)
    }

    @Test
    fun c3_iceConnectedNonAdmitted_controllerDoesNotOpenObligation() {
        val snapshot = anchorSnapshot()
        val targets = RecoveryEdgeProvider.project(snapshot)
        val obs = IceEdgeObservation("M02", "M03", iceConnected = true)
        assertFalse(ConferenceRecoveryBindingContract.iceObservationIsRecoverable(obs, snapshot))
        iceFailed("M05", targets)
        assertFalse(controller.factsForSession(sessionId).anyRecovering)
    }

    @Test
    fun c4_generationRollover_oldTargetsDoNotOpenNewObligation() {
        val s0 = anchorSnapshot(meshGeneration = 31L)
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = sessionId,
            hostModuleId = "M01",
            members = members4,
            rosterEpoch = 1L,
            meshGeneration = 32L
        )
        val rolled = RecoveryEdgeProvider.project(mesh, previousSnapshot = s0)
        assertTrue(rolled.targets.isEmpty())
        iceFailed("M02", rolled)
        assertFalse(controller.factsForSession(sessionId).anyRecovering)
    }

    @Test
    fun c5_explicitAuthorizedTransition_allowsHandoffMediaEdge() {
        val s0 = anchorSnapshot(anchor = "M01", meshGeneration = 31L, anchorEpoch = 10L)
        val s1 = ConferenceTopologyContract.failoverSnapshot(s0, "M02")
        val listed = MediaEdge("M01", "M02")
        val transition = AuthorizedTransition(
            fromMeshGeneration = 31L,
            toMeshGeneration = 32L,
            fromAnchorEpoch = 10L,
            toAnchorEpoch = 11L,
            affectedEdges = setOf(listed)
        )
        val with = RecoveryEdgeProvider.project(s1, s0, setOf(transition))
        val without = RecoveryEdgeProvider.project(s1, s0)
        assertTrue(ConferenceRecoveryBindingContract.admitsMediaEdge(with, sessionId, listed))
        assertFalse(ConferenceRecoveryBindingContract.admitsMediaEdge(without, sessionId, listed))
        iceFailed("M01", with)
        assertTrue(controller.factsForSession(sessionId).anyRecovering)
    }

    @Test
    fun c6_mesh_noConferenceRecoveryObligation() {
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = sessionId,
            hostModuleId = "M01",
            members = listOf("M01", "M02", "M03"),
            rosterEpoch = 1L,
            meshGeneration = 2L
        )
        iceFailed("M02", RecoveryEdgeProvider.project(mesh))
        assertFalse(controller.factsForSession(sessionId).anyRecovering)
    }

    @Test
    fun c7_nullTopologyTargets_r28OpensObligationUnchanged() {
        iceFailed("M01", targets = null)
        assertTrue(controller.factsForSession(sessionId).anyRecovering)
        assertTrue(controller.edgeObligationOpen(sessionId, "M01"))
    }
}
