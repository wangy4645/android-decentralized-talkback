package com.talkback.core.conference.transport

import com.talkback.core.conference.authority.AuthorityFactStore
import com.talkback.core.conference.authority.MediaKeyContextFact
import com.talkback.core.conference.authority.SourceAuthorizationFact
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.OpusDecodeSeam
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.wire.ConferenceWireEgress
import com.talkback.core.conference.wire.WireIngressResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 Slice 2 — one protected multicast packet through P03 pipeline.
 */
class Phase1MulticastMediaPipelineTest {
    @Test
    fun oneProtectedPacket_ingressToMixedPcm() {
        val store = AuthorityFactStore()
        installHarnessAuthority(store, sourceIdentity = "S1")
        val orchestrator = ConferenceMediaExecutionOrchestrator(store = store)
        orchestrator.authority.syncAllAdmittedToRuntime()

        val pcmMarker = ShortArray(160) { 12_000 }
        orchestrator.setDecodeSeam(
            OpusDecodeSeam { _, _, _, _ ->
                PcmFrame(pcmMarker.copyOf(), usableForMix = true)
            },
        )

        val egress = harnessEgress(initialSeq = 0x1001)
        val opus = ByteArray(72) { (it and 0xff).toByte() }
        val protected =
            egress.protectNext(opus) as ConferenceWireEgress.EgressResult.Protected

        val pipeline = ConferenceMulticastMediaPipeline.createHarness(orchestrator)
        val rxWallMs = 10_000L
        val result =
            pipeline.processProtectedDatagram(
                sourceIdentity = "S1",
                datagram = protected.udpPayload,
                rxWallMs = rxWallMs,
            )

        assertTrue(result.ingress is WireIngressResult.Accepted)
        assertEquals(FrameAdmitDisposition.QUEUED, result.frameAdmit)
        assertEquals(1, result.jitterDepth)
        assertTrue(result.mixCycle != null)
        assertEquals(setOf("S1"), result.mixCycle!!.topKIdentities)
        assertEquals(setOf("S1"), result.mixCycle!!.decodeInvocationIdentities)
        assertEquals(setOf("S1"), result.mixCycle!!.mixParticipantIdentities)
        assertEquals(1, result.mixCycle!!.mixedBlock.mixParticipantCount)
        assertTrue(result.mixCycle!!.mixedBlock.samples.contentEquals(pcmMarker))
        assertTrue(result.playoutObserved)
        assertTrue(result.packetToPlayoutLatencyUs >= 0L)
        assertTrue(result.decodeDurationUs >= 0L)
        assertTrue(result.mixDurationUs >= 0L)

        val snap = pipeline.observability.snapshot()
        assertEquals(1L, snap.ingressAccepted)
        assertEquals(1, snap.perSourceJitterDepth["S1"])
        assertTrue(snap.decodeDurationUs >= 0L)
        assertTrue(snap.mixDurationUs >= 0L)
        assertTrue(snap.playoutBudgetUsedUs >= 0L)
    }

    @Test
    fun wireIngressMediaMapper_derivesVoiceAndSlotFromAcceptedIngress() {
        val accepted =
            WireIngressResult.Accepted(
                plaintextPayload = ByteArray(4),
                packetIndex = 0x1001L,
                ssrc = 0x11223344,
                sequence = 0x1001,
                roc = 0,
                headerAndHe = harnessHeaderHe(),
            )
        val voice =
            WireIngressMediaMapper.voiceObservationFromHeader(
                sourceIdentity = "S1",
                incarnationId = 1L,
                headerAndHe = accepted.headerAndHe,
            )
        assertTrue(voice.voiceActive)

        val frame =
            WireIngressMediaMapper.mediaFrame(
                sourceIdentity = "S1",
                incarnationId = 1L,
                accepted = accepted,
                arrivalMs = 100L,
            )
        assertEquals(0x1001L, frame.mediaSlot)
        assertEquals(0x1001L * 20L, frame.mediaTimeMs)
    }

    private fun harnessEgress(initialSeq: Int): SourceScopedSrtpEgress {
        val masterKey = hex("000102030405060708090a0b0c0d0e0f")
        val masterSalt = hex("101112131415161718191a1b")
        return SourceScopedSrtpEgress(
            sourceIdentity = "S1",
            masterKey = masterKey,
            masterSalt = masterSalt,
            ssrc = 0x11223344,
            roc = 0,
            initialSeq = initialSeq,
            headerHeTemplate = SourceScopedSrtpEgress.buildHeaderHeTemplate(0x11223344),
        )
    }

    private fun harnessHeaderHe(): ByteArray =
        SourceScopedSrtpEgress.buildHeaderHeTemplate(0x11223344).also { header ->
            header[2] = 0x10
            header[3] = 0x01
        }

    private fun installHarnessAuthority(
        store: AuthorityFactStore,
        sourceIdentity: String,
    ) {
        store.acceptVerifiedKey(
            MediaKeyContextFact(
                mediaKeyEpoch = 1L,
                masterKey = hex("000102030405060708090a0b0c0d0e0f"),
                masterSalt = hex("101112131415161718191a1b"),
                keyContextHint64 = hex("bb49f2142c4c194a"),
            ),
        )
        store.acceptVerifiedSource(
            SourceAuthorizationFact(
                sourceIdentity = sourceIdentity,
                incarnationId = 1L,
                ssrc = 0x11223344,
                sourceAdmissionKey48 = hex("fe3e3a6cb214"),
                mediaKeyEpoch = 1L,
            ),
        )
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
