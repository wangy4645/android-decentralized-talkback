package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * ADR-0026: remote edge recovery must not block unrelated healthy publish paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceEdgeScopedTransmitBarrierTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Test
    fun m02Disconnect_m01HealthyToM03_canPublish() {
        val controller = controllerWithM02Recovering()
        Thread.sleep(100)
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))
        assertFalse(controller.isEdgeRecovering("sess-1", "M03"))

        assertTrue(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = false,
                    localPublisherReady = true
                )
            )
        )
    }

    @Test
    fun m02Disconnect_m03HealthyToM01_canPublish() {
        val controller = controllerWithM02Recovering()
        Thread.sleep(100)
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))

        assertTrue(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = false,
                    localPublisherReady = true
                )
            )
        )
    }

    @Test
    fun m02LocalTransportUnavailable_cannotPublish() {
        assertFalse(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = false,
                    localPublisherReady = false
                )
            )
        )
    }

    @Test
    fun remoteObligationOpen_doesNotBlockPublish() {
        val controller = newController()
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(3_500)
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))

        assertTrue(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = false,
                    localPublisherReady = true
                )
            )
        )
    }

    @Test
    fun localPublisherBroken_allPeersHealthy_cannotPublish() {
        assertFalse(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = false,
                    localPublisherReady = false
                )
            )
        )
    }

    @Test
    fun localMuted_cannotPublish_evenWhenPublisherReady() {
        assertFalse(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = true,
                    localPublisherReady = true
                )
            )
        )
    }

    private fun controllerWithM02Recovering(): ConferenceEdgeRecoveryController {
        val controller = newController()
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        return controller
    }

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
