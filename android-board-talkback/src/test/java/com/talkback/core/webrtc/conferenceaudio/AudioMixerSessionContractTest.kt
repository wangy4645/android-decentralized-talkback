package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1a-1 — session ownership / admission contract. */
class AudioMixerSessionContractTest {

    @Test
    fun admitBeforePush_required() {
        val events = mutableListOf<AudioMixerEvent>()
        val session = AudioMixerSession(onEvent = { events.add(it) })

        val push = session.push(AudioMixerInput("M02", PcmFrame.constantLevel(1_000)))
        assertTrue(push.isFailure)
        assertTrue(events.any { it is AudioMixerEvent.PushRejected })
    }

    @Test
    fun admit_push_render_happyPath() {
        val session = AudioMixerSession()
        assertTrue(session.admitSource("M02").isSuccess)
        assertTrue(session.push(AudioMixerInput("M02", PcmFrame.constantLevel(2_000))).isSuccess)
        val output = session.render()
        assertTrue(output.frame.peakAbs() > 0)
        assertTrue(output.contributingSourceIds.contains("M02"))
    }

    @Test
    fun duplicateAdmit_rejected() {
        val session = AudioMixerSession()
        assertTrue(session.admitSource("M02").isSuccess)
        assertTrue(session.admitSource("M02").isFailure)
    }

    @Test
    fun revokeSource_idempotentRampToRemoved() {
        val session = AudioMixerSession(mixer = AudioMixer(rampFrames = 2, silenceThreshold = 32))
        session.admitSource("M02")
        repeat(3) {
            session.push(AudioMixerInput("M02", PcmFrame.constantLevel(5_000)))
            session.render()
        }
        session.revokeSource("M02")
        session.revokeSource("M02")
        session.push(AudioMixerInput("M02", PcmFrame.constantLevel(5_000)))
        repeat(3) {
            session.push(AudioMixerInput("M02", PcmFrame.constantLevel(5_000)))
            session.render()
        }
        assertEquals(null, session.audioMixer.sourceState("M02"))
    }

    @Test
    fun pushAfterRevokeStarted_rejected() {
        val session = AudioMixerSession()
        session.admitSource("M02")
        session.revokeSource("M02")
        val result = session.push(AudioMixerInput("M02", PcmFrame.constantLevel(500)))
        assertTrue(result.isFailure)
    }

    @Test
    fun concurrentSourceChange_doesNotResetRenderClock() {
        val session = AudioMixerSession(mixer = AudioMixer(rampFrames = 0, silenceThreshold = 32))
        session.admitSource("A")
        session.admitSource("B")
        session.push(AudioMixerInput("A", PcmFrame.constantLevel(1_000)))
        val first = session.render()
        session.admitSource("C")
        session.revokeSource("A")
        session.push(AudioMixerInput("B", PcmFrame.constantLevel(2_000)))
        session.push(AudioMixerInput("C", PcmFrame.constantLevel(3_000)))
        val second = session.render()
        assertEquals(first.frame.samples.size, second.frame.samples.size)
        assertEquals(ConferencePcmFormat.CANONICAL.samplesPerFrame, second.frame.samples.size)
        assertFalse(second.contributingSourceIds.contains("A"))
        assertTrue(second.contributingSourceIds.contains("B"))
        assertTrue(second.contributingSourceIds.contains("C"))
    }
}
