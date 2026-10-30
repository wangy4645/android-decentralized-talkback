package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConferenceMediaSelectionRuntime
import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.transport.Profile02TxWireLevelComputer
import com.talkback.core.conference.transport.Profile02TxWireVoiceOctetResolver
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.SourceScopedSrtpEgress
import com.talkback.core.conference.transport.TxPacketVoiceMetadata
import com.talkback.core.conference.wire.ConferenceWireEgress
import com.talkback.core.conference.wire.RtpStructuralGate
import com.talkback.core.conference.transport.TxTalkspurtMarkerState
import com.talkback.core.webrtc.CapturePcmMeasurementPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3-Q4 desk — G3 TX wiring/header invariants (RED on production debt; GREEN on contract harness).
 *
 * VoiceActivity detector/threshold/hangover-ms are NOT authorized here.
 * Tests use explicit rawVoiceActive stubs only.
 */
class Profile01A3Q4TxWiringDeskTest {
    // --- Contract harness (GREEN) ---

    @Test
    fun desk1_samePcm960Reference_forDeriveAndEncode() {
        val pcm = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 8_192 }
        val marker = TxTalkspurtMarkerState()
        val record =
            Profile01G4TxEmissionHarness.emitCanonical20msTxFrame(
                pcm960 = pcm,
                markerState = marker,
                deriveVoiceOctet = { frame ->
                    Profile02TxWireVoiceOctetResolver.resolve(frame, rawVoiceActive = true)
                },
                encodeOpus = { frame -> OpusTestVectors.encodePcm(frame) },
                patchHeaderBeforeProtect = { _, _ -> },
            )
        assertSame(record.deriveInputRef, pcm)
        assertSame(record.encodeInputRef, pcm)
        assertTrue(record.opusPayload.isNotEmpty())
    }

    @Test
    fun desk2_digitalSilence_resolvesV0Level127_andStillEncodes() {
        val silence = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 0 }
        assertEquals(127, Profile02TxWireLevelComputer.audioLevelDbov(silence))
        val octet = Profile02TxWireVoiceOctetResolver.resolve(silence, rawVoiceActive = true)
        assertEquals(0x7F, octet)
        val marker = TxTalkspurtMarkerState()
        val record =
            Profile01G4TxEmissionHarness.emitCanonical20msTxFrame(
                pcm960 = silence,
                markerState = marker,
                deriveVoiceOctet = { frame ->
                    Profile02TxWireVoiceOctetResolver.resolve(frame, rawVoiceActive = true)
                },
                encodeOpus = { frame -> OpusTestVectors.encodePcm(frame) },
                patchHeaderBeforeProtect = { voice, _ ->
                    assertEquals(0x7F, voice)
                },
            )
        assertEquals(0x7F, record.voiceActiveAudioLevel)
        assertTrue(record.opusPayload.isNotEmpty())
    }

    @Test
    fun desk3_wireV0EndsTopKCandidacy_immediateSeatLoss() {
        val rt = ConferenceMediaSelectionRuntime()
        rt.install(AdmittedMediaSource(sourceIdentity = "M01", incarnationId = 1L))
        rt.install(AdmittedMediaSource(sourceIdentity = "M03", incarnationId = 1L))
        observe(rt, "M01", voiceActive = true, level = 10)
        observe(rt, "M03", voiceActive = true, level = 20)
        assertTrue(rt.selectTopK(0L).members.any { it.sourceIdentity == "M01" })
        observe(rt, "M01", voiceActive = false, level = 127)
        val top = rt.selectTopK(1L).members.map { it.sourceIdentity }
        assertFalse(top.contains("M01"))
        assertTrue(rt.isDecodeEligible("M03", 1L))
        assertFalse(rt.isDecodeEligible("M01", 1L))
    }

    @Test
    fun desk4_markerSetOnVoiceTransition0to1_only() {
        val marker = TxTalkspurtMarkerState()
        assertFalse(marker.next(false))
        assertFalse(marker.next(false))
        assertTrue(marker.next(true))
        assertFalse(marker.next(true))
        marker.reset()
        assertTrue(marker.next(true))
    }

    @Test
    fun desk4_harnessPatchesHeaderBeforeProtect_notAfter() {
        val pcm = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 4_096 }
        val marker = TxTalkspurtMarkerState()
        marker.next(false)
        val record =
            Profile01G4TxEmissionHarness.emitCanonical20msTxFrame(
                pcm960 = pcm,
                markerState = marker,
                deriveVoiceOctet = { frame ->
                    Profile02TxWireVoiceOctetResolver.resolve(frame, rawVoiceActive = true)
                },
                encodeOpus = { frame -> OpusTestVectors.encodePcm(frame) },
                patchHeaderBeforeProtect = { voice, m ->
                    assertTrue((voice and 0x80) != 0)
                    assertTrue(m)
                },
            )
        assertTrue(record.headerMarkerAtProtect)
    }

    @Test
    fun desk4_egress_appliesVoiceAndMarkerBeforeProtect() {
        val egress =
            SourceScopedSrtpEgress(
                sourceIdentity = "S1",
                masterKey = Phase1MediaHarness.masterKey.copyOf(),
                masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
                ssrc = 0x11223344,
                roc = 0,
                initialSeq = 0x1001,
                headerHeTemplate =
                    Phase1MediaHarness.buildHeaderHe(
                        ssrc = 0x11223344,
                        seq = 0x1001,
                        sourceAdmissionKey48 = ByteArray(6) { 0x11 },
                        voiceActiveAudioLevel = 0x7F,
                    ),
            )
        val opus = ByteArray(72) { 0 }
        val protected =
            egress.protectNext(
                opus,
                TxPacketVoiceMetadata(
                    voiceActiveAudioLevel = 0x8A,
                    marker = true,
                ),
            ) as ConferenceWireEgress.EgressResult.Protected
        val parsed = RtpStructuralGate.parse(protected.udpPayload)
        require(parsed is RtpStructuralGate.ParseResult.Ok)
        assertEquals(0x8A, parsed.parsed.voiceActiveAudioLevel)
        assertTrue(parsed.parsed.marker)
    }

    // --- Production wiring capability (G4 GREEN) ---

    @Test
    fun desk5_multicastTx_wireAuthorityMustBePostApm_notPreApmAdmTap() {
        assertEquals(
            CapturePcmMeasurementPoint.POST_SOFTWARE_APM_PRE_ENCODER,
            Profile01ShadowMulticastTransmitSeam.targetWireAuthorityMeasurementPoint(),
        )
        assertEquals(
            Profile01ShadowMulticastTransmitSeam.targetWireAuthorityMeasurementPoint(),
            Profile01ShadowMulticastTransmitSeam.currentWireAuthorityMeasurementPoint(),
        )
    }

    @Test
    fun desk5_multicastTx_mustNotUsePreApmMicFrameSource() {
        assertFalse(Profile01ShadowMulticastTransmitSeam.usesPreApmMicFrameSource())
    }

    @Test
    fun desk2_productionShadowTx_mustUsePerPacketVoiceMetadata_notFixedV1() {
        assertTrue(Profile01ShadowMulticastTransmitSeam.usesPerPacketVoiceMetadataWiring())
    }

    @Test
    fun desk4_egress_mustSupportPerPacketVoiceAndMarkerBeforeProtect() {
        assertTrue(SourceScopedSrtpEgress.SUPPORTS_PER_PACKET_VOICE_AND_MARKER)
    }

    private fun observe(
        rt: ConferenceMediaSelectionRuntime,
        id: String,
        voiceActive: Boolean,
        level: Int,
    ) {
        rt.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = id,
                incarnationId = 1L,
                voiceActive = voiceActive,
                audioLevel = level,
            ),
        )
    }
}
