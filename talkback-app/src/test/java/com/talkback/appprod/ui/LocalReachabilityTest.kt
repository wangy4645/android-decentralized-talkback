package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceParticipantDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

class LocalReachabilityTest {

    @Before
    fun setUp() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId != "M01"
        }
    }

    @After
    fun tearDown() {
        MeetingPresenceDisplay.receivePathLivenessProvider = NoOpReceivePathLivenessProvider
    }

    @Test
    fun r30_j_1_receivePathLiveTrueWhileRecovering_notReconnecting_hintNull() {
        val reachability = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = true,
            recovering = true,
            mediaUnavailable = false,
            everConnected = true
        )
        assertNotEquals(LocalReachability.ParticipantPresenceState.RECONNECTING, reachability.state)
        assertEquals(LocalReachability.ParticipantPresenceState.ONLINE, reachability.state)

        val hint = MeetingPresenceDisplay.aggregateHintFromReachabilities(
            reachabilities = listOf("M01" to reachability),
            localCaptureBlocked = false
        )
        assertNull(hint)
    }

    @Test
    fun r30_j_2_hintOnlyFromLocalReachabilityAggregate_notRecoveringPeers() {
        val m01Reachability = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = false,
            recovering = false,
            mediaUnavailable = false,
            everConnected = true
        )
        assertEquals(LocalReachability.ParticipantPresenceState.RECONNECTING, m01Reachability.state)

        val hintFromReachability = MeetingPresenceDisplay.aggregateHintFromReachabilities(
            reachabilities = listOf(
                "M01" to m01Reachability,
                "M02" to online("M02"),
                "M03" to online("M03")
            ),
            localCaptureBlocked = false
        )
        assertEquals("M01 reconnecting...", hintFromReachability)

        val presentation = MeetingPresenceDisplay.resolveParticipantPresentation(
            remoteFacts(
                sessionId = "sess-test",
                moduleId = "M01",
                displayState = ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
                everConnected = true,
                isRecoveringPeer = false
            )
        )
        assertEquals(
            hintFromReachability,
            MeetingPresenceDisplay.aggregateHintFromReachabilities(
                reachabilities = listOf(
                    "M01" to presentation.reachability,
                    "M02" to online("M02"),
                    "M03" to online("M03")
                ),
                localCaptureBlocked = false
            )
        )
        assertEquals(MeetingPresenceDisplay.ParticipantAvailabilityKind.RECONNECTING, presentation.availabilityKind)
    }

    @Test
    fun r30_j_out_asymmetricMeshAllowed_noCrossDeviceAssertion() {
        val m01OnM03 = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = false,
            recovering = true,
            mediaUnavailable = false,
            everConnected = true
        )
        val m03OnM01 = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = true,
            recovering = false,
            mediaUnavailable = false,
            everConnected = true
        )
        assertEquals(LocalReachability.ParticipantPresenceState.RECONNECTING, m01OnM03.state)
        assertEquals(LocalReachability.ParticipantPresenceState.ONLINE, m03OnM01.state)
    }

    @Test
    fun g_r30_j_3_pureProjection_noTimerOrMutableState() {
        val clazz = LocalReachability::class.java
        assertTrue(Modifier.isFinal(clazz.modifiers))
        val forbiddenFields = clazz.declaredFields.filter { field ->
            field.name != "INSTANCE" &&
                (field.name.contains("timer", ignoreCase = true) ||
                    field.name.contains("cache", ignoreCase = true) ||
                    field.name.contains("latch", ignoreCase = true) ||
                    field.name.contains("deadline", ignoreCase = true))
        }
        assertTrue(forbiddenFields.isEmpty())
        assertEquals(
            LocalReachability.Result(LocalReachability.ParticipantPresenceState.ONLINE),
            LocalReachability.resolve(
                membership = LocalReachability.MembershipState.JOINED,
                receivePathLive = true,
                recovering = false,
                mediaUnavailable = false,
                everConnected = true
            )
        )
    }

    private fun online(moduleId: String): LocalReachability.Result =
        LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = true,
            recovering = false,
            mediaUnavailable = false,
            everConnected = true
        ).also { require(moduleId.isNotBlank()) }

    private fun remoteFacts(
        sessionId: String = "sess-test",
        moduleId: String,
        displayState: ConferenceParticipantDisplayState,
        everConnected: Boolean,
        isRecoveringPeer: Boolean = false,
        mediaUnavailablePeer: Boolean = false
    ) = MeetingPresenceDisplay.ParticipantPresentationFacts(
        sessionId = sessionId,
        moduleId = moduleId,
        isLocal = false,
        membership = ConferenceMembershipLifecycle.JOINED,
        displayState = displayState,
        everConnected = everConnected,
        isRecoveringPeer = isRecoveringPeer,
        mediaUnavailablePeer = mediaUnavailablePeer,
        speaking = false
    )
}
