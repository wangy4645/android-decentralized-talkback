package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantMediaModePolicyTest {

    @Test
    fun anchor_resolvesLocalAndRemote() {
        val mode = ParticipantMediaModePolicy.resolve(
            localModuleId = "M01",
            anchorModuleId = "M01",
            participantModuleId = "M01"
        )
        assertEquals(ParticipantMediaMode.LOCAL_AND_REMOTE, mode)
        assertTrue(ParticipantMediaModePolicy.includesLocalMicrophone(mode))
        assertTrue(ParticipantMediaModePolicy.includesRemoteRelay(mode))
    }

    @Test
    fun remoteParticipant_resolvesRemoteRelay() {
        val mode = ParticipantMediaModePolicy.resolve(
            localModuleId = "M01",
            anchorModuleId = "M01",
            participantModuleId = "M02"
        )
        assertEquals(ParticipantMediaMode.REMOTE_RELAY, mode)
        assertFalse(ParticipantMediaModePolicy.includesLocalMicrophone(mode))
        assertTrue(ParticipantMediaModePolicy.includesRemoteRelay(mode))
    }

    @Test
    fun nonAnchorLocal_resolvesLocal() {
        val mode = ParticipantMediaModePolicy.resolve(
            localModuleId = "M02",
            anchorModuleId = "M01",
            participantModuleId = "M02"
        )
        assertEquals(ParticipantMediaMode.LOCAL, mode)
        assertTrue(ParticipantMediaModePolicy.includesLocalMicrophone(mode))
        assertFalse(ParticipantMediaModePolicy.includesRemoteRelay(mode))
    }
}
