package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceRuntimeProjectorTest {

    private fun input(
        sessionAccepted: Boolean = false,
        transitionTerminalReady: Boolean = false,
        connectedRemoteMediaCount: Int = 0,
        awaitingAdditionalParticipants: Boolean = false,
        mediaRecovering: Boolean = false,
        edgeRecovering: Boolean = false,
        isConferenceHost: Boolean = false,
        authorityReachable: Boolean = false
    ) = ConferenceRuntimeProjector.Input(
        sessionAccepted = sessionAccepted,
        transitionTerminalReady = transitionTerminalReady,
        connectedRemoteMediaCount = connectedRemoteMediaCount,
        awaitingAdditionalParticipants = awaitingAdditionalParticipants,
        mediaRecovering = mediaRecovering,
        edgeRecovering = edgeRecovering,
        isConferenceHost = isConferenceHost,
        authorityReachable = authorityReachable
    )

    @Test
    fun phase_notAccepted_idle() {
        val state = ConferenceRuntimeProjector.project(input(sessionAccepted = false))
        assertEquals(ConferenceRuntimePhase.IDLE, state.phase)
    }

    @Test
    fun phase_acceptedTransitionNotReady_connecting() {
        val state = ConferenceRuntimeProjector.project(
            input(sessionAccepted = true, transitionTerminalReady = false)
        )
        assertEquals(ConferenceRuntimePhase.CONNECTING, state.phase)
    }

    @Test
    fun gate_g1_hostSoloRemoteZero_activeNotConnecting() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 0,
                isConferenceHost = true,
                awaitingAdditionalParticipants = true
            )
        )
        assertEquals(ConferenceRuntimePhase.ACTIVE, state.phase)
        assertTrue(state.awaitingAdditionalParticipants)
    }

    @Test
    fun gate_g2_participantPeerMediaWithoutAuthority_connecting() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 2,
                isConferenceHost = false,
                authorityReachable = false
            )
        )
        assertEquals(ConferenceRuntimePhase.CONNECTING, state.phase)
    }

    @Test
    fun gate_g3_participantAuthorityReachable_active() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 0,
                isConferenceHost = false,
                authorityReachable = true
            )
        )
        assertEquals(ConferenceRuntimePhase.ACTIVE, state.phase)
    }

    @Test
    fun gate_g4_mediaRecovering_recovering() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 3,
                isConferenceHost = true,
                mediaRecovering = true
            )
        )
        assertEquals(ConferenceRuntimePhase.RECOVERING, state.phase)
        assertTrue(state.mediaRecovering)
    }

    @Test
    fun gate_g5_edgeRecovering_activeWithDegradedFlag() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 0,
                isConferenceHost = false,
                authorityReachable = false,
                edgeRecovering = true
            )
        )
        assertEquals(ConferenceRuntimePhase.ACTIVE, state.phase)
        assertTrue(state.edgeRecovering)
        assertTrue(state.mediaRecovering)
    }

    @Test
    fun phase_hostWithConnectedRemotes_active() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 2,
                isConferenceHost = true
            )
        )
        assertEquals(ConferenceRuntimePhase.ACTIVE, state.phase)
    }

    @Test
    fun phase_activeCoexistsWithAwaiting() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 2,
                isConferenceHost = true,
                awaitingAdditionalParticipants = true
            )
        )
        assertEquals(ConferenceRuntimePhase.ACTIVE, state.phase)
        assertTrue(state.awaitingAdditionalParticipants)
    }

    @Test
    fun phase_recoveringOverridesConnecting() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = false,
                mediaRecovering = true
            )
        )
        assertEquals(ConferenceRuntimePhase.RECOVERING, state.phase)
    }

    @Test
    fun state_passesThroughInputFields() {
        val state = ConferenceRuntimeProjector.project(
            input(
                sessionAccepted = true,
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 2,
                awaitingAdditionalParticipants = false,
                mediaRecovering = false,
                isConferenceHost = true
            )
        )
        assertEquals(ConferenceRuntimePhase.ACTIVE, state.phase)
        assertFalse(state.mediaRecovering)
        assertFalse(state.awaitingAdditionalParticipants)
        assertTrue(state.transitionTerminalReady)
        assertEquals(2, state.connectedRemoteMediaCount)
    }
}
