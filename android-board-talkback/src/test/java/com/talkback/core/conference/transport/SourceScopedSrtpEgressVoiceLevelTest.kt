package com.talkback.core.conference.transport

import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.wire.ConferenceWireConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceScopedSrtpEgressVoiceLevelTest {
    @Test
    fun protectNextAdvancesSeqWithoutOverwritingVoiceOctet() {
        val fixture = Phase1MediaHarness.threeSourceFixtures.first()
        val egress = Phase1MediaHarness.egressFor(fixture, initialSeq = 0x5000)
        egress.setVoiceActiveAudioLevel(0x8A)
        val payload = OpusTestVectors.encodeTone(440.0)
        require(payload.size <= ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS)
        val first = egress.protectNext(payload)
        assertTrue(first is com.talkback.core.conference.wire.ConferenceWireEgress.EgressResult.Protected)
        assertEquals(0x8A, egress.voiceActiveAudioLevel())
        egress.setVoiceActiveAudioLevel(0x45)
        val second = egress.protectNext(payload)
        assertTrue(second is com.talkback.core.conference.wire.ConferenceWireEgress.EgressResult.Protected)
        assertEquals(0x45, egress.voiceActiveAudioLevel())
        assertEquals(0x5002, egress.currentSeq)
    }
}
