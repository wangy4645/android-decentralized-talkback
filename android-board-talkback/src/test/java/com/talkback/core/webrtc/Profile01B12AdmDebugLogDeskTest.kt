package com.talkback.core.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-12: only ADM tags are forwarded; no audio-parameter change. */
class Profile01B12AdmDebugLogDeskTest {
    @Test
    fun forwardsAdmTagsOnly() {
        assertTrue(WebRtcAdmDebugLog.shouldForward("JavaAudioDeviceModule"))
        assertTrue(WebRtcAdmDebugLog.shouldForward("WebRtcAudioRecord"))
        assertTrue(WebRtcAdmDebugLog.shouldForward("WebRtcAudioRecordExternal"))
        assertTrue(WebRtcAdmDebugLog.shouldForward("WebRtcAudioEffectsExternal"))
        assertFalse(WebRtcAdmDebugLog.shouldForward("PeerConnection"))
        assertFalse(WebRtcAdmDebugLog.shouldForward("Talkback"))
    }
}
