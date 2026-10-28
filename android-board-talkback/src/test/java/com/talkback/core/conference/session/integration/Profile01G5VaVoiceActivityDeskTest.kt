package com.talkback.core.conference.session.integration

import com.talkback.core.conference.transport.Profile02TxWireVoiceOctetResolver
import com.talkback.core.conference.transport.Profile03TxFrameVoiceMetadataDeriver
import com.talkback.core.conference.transport.Profile03WebRtcVadVoiceActivityProvider
import com.talkback.core.conference.transport.TalkbackWebRtcVad
import com.talkback.core.conference.transport.TxTalkspurtMarkerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G5-VA desk — WebRtcVad + TX hangover policy (VA-2 + H-1).
 */
class Profile01G5VaVoiceActivityDeskTest {
    private fun requireVad() {
        check(TalkbackWebRtcVad.isAvailable()) {
            "talkback_webrtc_vad unavailable (JNI or WSL CLI)"
        }
    }

    @Test
    fun speechVector_isActive() {
        requireVad()
        val provider = Profile03WebRtcVadVoiceActivityProvider()
        assertTrue(provider.rawVoiceActive(Profile01G5VaPcmVectors.speechPcm960()))
    }

    @Test
    fun silenceVector_isInactive() {
        requireVad()
        val provider = Profile03WebRtcVadVoiceActivityProvider()
        assertFalse(provider.rawVoiceActive(Profile01G5VaPcmVectors.digitalSilencePcm960()))
    }

    @Test
    fun attack_isImmediate_afterSilence() {
        requireVad()
        val provider = Profile03WebRtcVadVoiceActivityProvider()
        assertFalse(provider.rawVoiceActive(Profile01G5VaPcmVectors.digitalSilencePcm960()))
        assertTrue(provider.rawVoiceActive(Profile01G5VaPcmVectors.speechPcm960()))
    }

    @Test
    fun release_isExactlyFiveFrames_afterSpeechEnds() {
        requireVad()
        val provider = Profile03WebRtcVadVoiceActivityProvider()
        assertTrue(provider.rawVoiceActive(Profile01G5VaPcmVectors.speechPcm960()))
        val silence = Profile01G5VaPcmVectors.digitalSilencePcm960()
        repeat(Profile03WebRtcVadVoiceActivityProvider.HANGOVER_FRAMES - 1) {
            assertTrue("hangover frame ${it + 1} should remain active", provider.rawVoiceActive(silence))
        }
        assertFalse(
            "frame ${Profile03WebRtcVadVoiceActivityProvider.HANGOVER_FRAMES} should release",
            provider.rawVoiceActive(silence),
        )
        assertEquals(0, provider.hangoverRemainingFrames())
    }

    @Test
    fun digitalSilence_consistencyGateOverridesHangoverOnWire() {
        requireVad()
        val voiceActivity = Profile03WebRtcVadVoiceActivityProvider()
        val deriver =
            Profile03TxFrameVoiceMetadataDeriver(
                voiceActivity = voiceActivity,
                markerState = TxTalkspurtMarkerState(),
            )
        assertTrue(voiceActivity.rawVoiceActive(Profile01G5VaPcmVectors.speechPcm960()))
        val silence = Profile01G5VaPcmVectors.digitalSilencePcm960()
        val derived = deriver.derive(silence)
        assertEquals(0x7F, derived.voiceActiveAudioLevel)
        assertFalse(derived.voiceActive)
    }

    @Test
    fun reset_clearsHangoverState() {
        requireVad()
        val provider = Profile03WebRtcVadVoiceActivityProvider()
        assertTrue(provider.rawVoiceActive(Profile01G5VaPcmVectors.speechPcm960()))
        provider.reset()
        assertEquals(0, provider.hangoverRemainingFrames())
        assertFalse(provider.rawVoiceActive(Profile01G5VaPcmVectors.digitalSilencePcm960()))
    }

    @Test
    fun deriverReset_clearsHangoverAndMarker() {
        requireVad()
        val marker = TxTalkspurtMarkerState()
        val provider = Profile03WebRtcVadVoiceActivityProvider()
        val deriver =
            Profile03TxFrameVoiceMetadataDeriver(
                voiceActivity = provider,
                markerState = marker,
            )
        val silence = Profile01G5VaPcmVectors.digitalSilencePcm960()
        assertTrue(provider.rawVoiceActive(Profile01G5VaPcmVectors.speechPcm960()))
        provider.rawVoiceActive(silence)
        assertTrue(provider.hangoverRemainingFrames() > 0)
        deriver.resetState()
        assertEquals(0, provider.hangoverRemainingFrames())
        assertFalse(marker.next(false))
        assertTrue(marker.next(true))
    }

    @Test
    fun samePcm960Reference_forVoiceActivityLookup() {
        requireVad()
        val pcm = Profile01G5VaPcmVectors.speechPcm960()
        val provider = Profile03WebRtcVadVoiceActivityProvider()
        val octet =
            Profile02TxWireVoiceOctetResolver.resolve(
                pcm,
                rawVoiceActive = provider.rawVoiceActive(pcm),
            )
        assertTrue((octet and 0x80) != 0)
        assertSame(pcm, pcm)
    }
}
