package com.talkback.core.webrtc

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-18: only M02 model leaves VOICE_COMMUNICATION for MIC (avoid voip_tx). */
class Profile01B18VoipTxSourceDeskTest {
    @Test
    fun m02UsesMicOthersKeepVoiceCommunication() {
        assertTrue(B18VoipTxCaptureSource.isM02Board("g80l_221_v1_fhd"))
        assertEquals(
            MediaRecorder.AudioSource.MIC,
            B18VoipTxCaptureSource.audioSource("g80l_221_v1_fhd"),
        )
        assertFalse(B18VoipTxCaptureSource.isM02Board("DSJ_A7S"))
        assertFalse(B18VoipTxCaptureSource.isM02Board("MLD-AL10"))
        assertFalse(B18VoipTxCaptureSource.isM02Board("ELS-AN00"))
        assertEquals(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            B18VoipTxCaptureSource.audioSource("ELS-AN00"),
        )
    }
}