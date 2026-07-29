package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceBarrierSnapshotTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Test
    fun snapshot_noRecovery_canPublish_emptyBlockedReasons() {
        val controller = newController()
        val snapshot = snapshot(
            controller = controller,
            connectedPeers = setOf("M02", "M03"),
            gateInput = gateInput(publisherReady = true)
        )
        assertTrue(snapshot.canPublish)
        assertTrue(snapshot.blockedReasons.isEmpty())
        assertEquals(ConferenceBarrierPolicy.EDGE_SCOPED, snapshot.barrierPolicy)
    }

    @Test
    fun snapshot_edgeRecovering_doesNotBlockPublish() {
        val controller = newController()
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(100)
        val snapshot = snapshot(
            controller = controller,
            connectedPeers = setOf("M02"),
            gateInput = gateInput(publisherReady = true)
        )
        assertTrue(snapshot.canPublish)
        assertEquals(setOf("M03"), snapshot.recoveringPeers)
        assertTrue(snapshot.blockedReasons.isEmpty())
    }

    @Test
    fun snapshot_noConnectedPeers_blocksWithoutPeerRecoveryAttribution() {
        val controller = newController()
        val snapshot = snapshot(
            controller = controller,
            connectedPeers = emptySet(),
            gateInput = gateInput(publisherReady = false)
        )
        assertFalse(snapshot.canPublish)
        assertTrue(snapshot.blockedReasons.none { it.recovering })
        assertTrue(snapshot.blockedReasons.all { !it.iceConnected })
    }

    @Test
    fun snapshot_failedResidency_obligationOpen_doesNotBlockPublish() {
        val controller = newController()
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(350)
        val snapshot = snapshot(
            controller = controller,
            connectedPeers = setOf("M02", "M03"),
            gateInput = gateInput(publisherReady = true)
        )
        assertTrue(snapshot.canPublish)
        assertEquals(setOf("M03"), snapshot.obligationOpenPeers)
        assertTrue(snapshot.blockedReasons.isEmpty())
    }

    @Test
    fun toLogLine_includesBlockedPeerFields() {
        val snapshot = ConferenceBarrierSnapshot(
            sessionId = "sess-1",
            barrierPolicy = ConferenceBarrierPolicy.EDGE_SCOPED,
            canPublish = false,
            blockedReasons = listOf(
                ConferenceBarrierPeerReason(
                    peer = "M03",
                    recovering = false,
                    obligationOpen = true,
                    failedMediaRecovery = true,
                    iceConnected = false,
                    mediaState = ConferenceBarrierMediaState.FAILED
                )
            ),
            joinedPeers = setOf("M02", "M03"),
            connectedPeers = setOf("M02"),
            recoveringPeers = emptySet(),
            obligationOpenPeers = setOf("M03"),
            failedPeers = setOf("M03"),
            anyRecovering = false
        )
        val line = snapshot.toLogLine()
        assertTrue(line.contains("canPublish=false"))
        assertTrue(line.contains("policy=EDGE_SCOPED"))
        assertTrue(line.contains("M03{recovering=false,obligationOpen=true"))
    }

    private fun snapshot(
        controller: ConferenceEdgeRecoveryController,
        connectedPeers: Set<String>,
        gateInput: ConferenceMediaTransmitGate.Input
    ) = ConferenceBarrierDiagnostics.snapshot(
        sessionId = "sess-1",
        joinedPeers = setOf("M02", "M03"),
        connectedPeers = connectedPeers,
        controller = controller,
        isIceConnected = { peer -> peer in connectedPeers },
        gateInput = gateInput
    )

    private fun gateInput(publisherReady: Boolean) = ConferenceMediaTransmitGate.Input(
        localConferenceActive = true,
        localMuted = false,
        localPublisherReady = publisherReady
    )

    private fun newController(): ConferenceEdgeRecoveryController =
        ConferenceEdgeRecoveryController(
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 300L,
            observationWindowMs = 5_000L,
            scheduler = scheduler,
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true }
        )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )
}
