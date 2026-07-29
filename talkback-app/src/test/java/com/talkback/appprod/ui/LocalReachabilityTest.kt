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

            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02", "M03")
        }
    }

    @After
    fun tearDown() {
        MeetingPresenceDisplay.receivePathLivenessProvider = NoOpReceivePathLivenessProvider
    }

    @Test
    fun r30_j_1_edgeRecovering_vetoesReceivePathLive_showsReconnecting() {
        val reachability = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = true,
            recovering = true,
            mediaUnavailable = false,
            mediaEverLive = true
        )
        assertEquals(LocalReachability.ParticipantPresenceState.RECONNECTING, reachability.state)

        val hint = MeetingPresenceDisplay.aggregateHintFromReachabilities(
            reachabilities = listOf("M01" to reachability),
            localCaptureBlocked = false
        )
        assertEquals("M01 reconnecting...", hint)
    }

    @Test
    fun rule2_edgeVetoesMedia_tableDriven() {
        data class Row(
            val recovering: Boolean,
            val mediaUnavailable: Boolean,
            val receivePathLive: Boolean,
            val mediaEverLive: Boolean,
            val expected: LocalReachability.ParticipantPresenceState
        )
        val rows = listOf(
            // session efe1d26d / 56c565bb: edge recovering while PCM still live
            Row(recovering = true, mediaUnavailable = false, receivePathLive = true, mediaEverLive = true,
                expected = LocalReachability.ParticipantPresenceState.RECONNECTING),
            // ICE FAILED + mediaUnavailable while receivePathLive sticky true
            Row(recovering = false, mediaUnavailable = true, receivePathLive = true, mediaEverLive = true,
                expected = LocalReachability.ParticipantPresenceState.RECONNECTING),
            Row(recovering = true, mediaUnavailable = true, receivePathLive = true, mediaEverLive = true,
                expected = LocalReachability.ParticipantPresenceState.RECONNECTING),
            // healthy path
            Row(recovering = false, mediaUnavailable = false, receivePathLive = true, mediaEverLive = true,
                expected = LocalReachability.ParticipantPresenceState.ONLINE),
            // first join, media already live
            Row(recovering = false, mediaUnavailable = false, receivePathLive = true, mediaEverLive = false,
                expected = LocalReachability.ParticipantPresenceState.ONLINE),
            // media gap after prior live PCM
            Row(recovering = false, mediaUnavailable = false, receivePathLive = false, mediaEverLive = true,
                expected = LocalReachability.ParticipantPresenceState.RECONNECTING),
            Row(recovering = false, mediaUnavailable = false, receivePathLive = false, mediaEverLive = false,
                expected = LocalReachability.ParticipantPresenceState.JOINING),
        )
        rows.forEachIndexed { index, row ->
            val actual = LocalReachability.resolve(
                membership = LocalReachability.MembershipState.JOINED,
                receivePathLive = row.receivePathLive,
                recovering = row.recovering,
                mediaUnavailable = row.mediaUnavailable,
                mediaEverLive = row.mediaEverLive
            ).state
            assertEquals("row $index", row.expected, actual)
        }
    }

    @Test
    fun g_hist_split_transportHistoryDoesNotImplyReconnecting() {
        // soak 17:08:21 — ICE up, PCM not yet live: transport history must not matter.
        val reachability = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = false,
            recovering = false,
            mediaUnavailable = false,
            mediaEverLive = false
        )
        assertEquals(LocalReachability.ParticipantPresenceState.JOINING, reachability.state)
    }

    @Test
    fun r30_j_2_hintOnlyFromLocalReachabilityAggregate_notRecoveringPeers() {
        val m01Reachability = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = false,
            recovering = false,
            mediaUnavailable = false,
            mediaEverLive = true
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
    fun g_r30_j_regression_receivePathLiveTrueWhileEverConnected_notReconnecting() {
        val reachability = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = true,
            recovering = false,
            mediaUnavailable = false,
            mediaEverLive = true
        )
        assertEquals(LocalReachability.ParticipantPresenceState.ONLINE, reachability.state)

        val hint = MeetingPresenceDisplay.aggregateHintFromReachabilities(
            reachabilities = listOf(
                "M02" to reachability,
                "M03" to online("M03")
            ),
            localCaptureBlocked = false
        )
        assertNull(hint)
    }

    @Test
    fun r30_j_out_asymmetricMeshAllowed_noCrossDeviceAssertion() {
        val m01OnM03 = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = false,
            recovering = true,
            mediaUnavailable = false,
            mediaEverLive = true
        )
        val m03OnM01 = LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = true,
            recovering = false,
            mediaUnavailable = false,
            mediaEverLive = true
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
                mediaEverLive = true
            )
        )
    }

    private fun online(moduleId: String): LocalReachability.Result =
        LocalReachability.resolve(
            membership = LocalReachability.MembershipState.JOINED,
            receivePathLive = true,
            recovering = false,
            mediaUnavailable = false,
            mediaEverLive = true
        ).also { require(moduleId.isNotBlank()) }

    private fun remoteFacts(
        sessionId: String = "sess-test",
        moduleId: String,
        displayState: ConferenceParticipantDisplayState,
        isRecoveringPeer: Boolean = false,
        mediaUnavailablePeer: Boolean = false
    ) = MeetingPresenceDisplay.ParticipantPresentationFacts(
        sessionId = sessionId,
        moduleId = moduleId,
        isLocal = false,
        membership = ConferenceMembershipLifecycle.JOINED,
        displayState = displayState,
        isRecoveringPeer = isRecoveringPeer,
        mediaUnavailablePeer = mediaUnavailablePeer,
        speaking = false
    )
}
