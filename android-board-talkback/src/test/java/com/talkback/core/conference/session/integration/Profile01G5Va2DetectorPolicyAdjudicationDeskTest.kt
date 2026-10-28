package com.talkback.core.conference.session.integration

import com.talkback.core.conference.transport.Profile01G5Va2DetectorPolicyAdjudication
import com.talkback.core.conference.transport.TalkbackWebRtcVad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G5-VA2 desk — mode 2 vs 3 adjudication harness (VA-2 family only).
 */
class Profile01G5Va2DetectorPolicyAdjudicationDeskTest {
    private fun requireVad() {
        check(TalkbackWebRtcVad.isAvailable()) {
            "talkback_webrtc_vad unavailable (JNI or WSL CLI)"
        }
    }

    @Test
    fun digitalSilence_isInactiveForBothModes() {
        requireVad()
        val frames = Profile01G5VaPcmVectors.repeatFrames(Profile01G5VaPcmVectors.digitalSilencePcm960(), 25)
        val stats = Profile01G5Va2DetectorPolicyAdjudication.evaluateSegment("silence", frames)
        assertEquals(0.0, stats.mode2.hangActiveRate, 0.0)
        assertEquals(0.0, stats.mode3.hangActiveRate, 0.0)
    }

    @Test
    fun speech_isActiveForBothModes() {
        requireVad()
        val frames = Profile01G5VaPcmVectors.repeatFrames(Profile01G5VaPcmVectors.speechPcm960(), 25)
        val stats = Profile01G5Va2DetectorPolicyAdjudication.evaluateSegment("speech", frames)
        assertTrue(stats.mode2.hangActiveRate >= 0.9)
        assertTrue(stats.mode3.hangActiveRate >= 0.9)
    }

    @Test
    fun adjudication_acceptsMode3_whenNoiseOnlyFalsePositiveClears() {
        requireVad()
        val idle =
            Profile01G5Va2DetectorPolicyAdjudication.evaluateSegment(
                "idle",
                Profile01G5VaPcmVectors.repeatFrames(Profile01G5VaPcmVectors.digitalSilencePcm960(), 50),
            )
        val speech =
            Profile01G5Va2DetectorPolicyAdjudication.evaluateSegment(
                "speech",
                Profile01G5VaPcmVectors.repeatFrames(Profile01G5VaPcmVectors.speechPcm960(), 50),
            )
        val report = Profile01G5Va2DetectorPolicyAdjudication.adjudicate(idle, speech)
        assertEquals(Profile01G5Va2DetectorPolicyAdjudication.Verdict.ACCEPT_MODE_3, report.verdict)
    }

    @Test
    fun lowLevelNoise_reportsModeDelta_forManualReview() {
        requireVad()
        val frames =
            Profile01G5VaPcmVectors.repeatFrames(
                Profile01G5VaPcmVectors.lowLevelNoisePcm960(),
                50,
            )
        val stats = Profile01G5Va2DetectorPolicyAdjudication.evaluateSegment("noise", frames)
        // Desk signal only — field M02 evidence is authoritative for G5-VA2.
        assertTrue(stats.mode2.hangActiveRate >= stats.mode3.hangActiveRate)
    }
}
