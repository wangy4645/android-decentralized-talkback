package com.talkback.core.conference.transport

import com.talkback.core.conference.authority.AuthorityFactStore
import com.talkback.core.conference.authority.MediaKeyContextFact
import com.talkback.core.conference.authority.SourceAuthorizationFact
import com.talkback.core.conference.runtime.ConcentusOpusDecoderSeam
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.wire.ConferenceWireConstants
import com.talkback.core.conference.wire.ConferenceWireEgress

/**
 * Shared fixtures for Phase 1 Slice 2/3 media pipeline tests.
 * PUBLIC TEST KEYS ONLY.
 */
object Phase1MediaHarness {
    val masterKey: ByteArray = hex("000102030405060708090a0b0c0d0e0f")
    val masterSalt: ByteArray = hex("101112131415161718191a1b")
    val keyContextHint64: ByteArray = hex("bb49f2142c4c194a")

    data class SourceFixture(
        val sourceIdentity: String,
        val incarnationId: Long = 1L,
        val ssrc: Int,
        val sourceAdmissionKey48: ByteArray,
        val audioLevel: Int,
        val frequencyHz: Double,
    )

    val threeSourceFixtures: List<SourceFixture> =
        listOf(
            SourceFixture(
                sourceIdentity = "S1",
                ssrc = 0x11223344,
                sourceAdmissionKey48 = hex("fe3e3a6cb214"),
                audioLevel = 10,
                frequencyHz = 440.0,
            ),
            SourceFixture(
                sourceIdentity = "S2",
                ssrc = 0x11223345,
                sourceAdmissionKey48 = hex("fe3e3a6cb215"),
                audioLevel = 20,
                frequencyHz = 554.0,
            ),
            SourceFixture(
                sourceIdentity = "S3",
                ssrc = 0x11223346,
                sourceAdmissionKey48 = hex("fe3e3a6cb216"),
                audioLevel = 30,
                frequencyHz = 659.0,
            ),
        )

    /** S4 — synthetic receiver-scaling probe (not a physical sender). */
    val syntheticSource4Fixture: SourceFixture =
        SourceFixture(
            sourceIdentity = "S4",
            ssrc = 0x11223347,
            sourceAdmissionKey48 = hex("fe3e3a6cb217"),
            audioLevel = 40,
            frequencyHz = 784.0,
        )

    /** S4–S8 synthetic fixtures for 8-source receiver scaling (not physical senders). */
    val syntheticScalingFixtures: List<SourceFixture> =
        listOf(
            syntheticSource4Fixture,
            SourceFixture(
                sourceIdentity = "S5",
                ssrc = 0x11223348,
                sourceAdmissionKey48 = hex("fe3e3a6cb218"),
                audioLevel = 45,
                frequencyHz = 880.0,
            ),
            SourceFixture(
                sourceIdentity = "S6",
                ssrc = 0x11223349,
                sourceAdmissionKey48 = hex("fe3e3a6cb219"),
                audioLevel = 50,
                frequencyHz = 988.0,
            ),
            SourceFixture(
                sourceIdentity = "S7",
                ssrc = 0x1122334a,
                sourceAdmissionKey48 = hex("fe3e3a6cb21a"),
                audioLevel = 55,
                frequencyHz = 1047.0,
            ),
            SourceFixture(
                sourceIdentity = "S8",
                ssrc = 0x1122334b,
                sourceAdmissionKey48 = hex("fe3e3a6cb21b"),
                audioLevel = 60,
                frequencyHz = 1175.0,
            ),
            SourceFixture(
                sourceIdentity = "S9",
                ssrc = 0x1122334c,
                sourceAdmissionKey48 = hex("fe3e3a6cb21c"),
                audioLevel = 65,
                frequencyHz = 1319.0,
            ),
            SourceFixture(
                sourceIdentity = "S10",
                ssrc = 0x1122334d,
                sourceAdmissionKey48 = hex("fe3e3a6cb21d"),
                audioLevel = 70,
                frequencyHz = 1397.0,
            ),
        )

    /** 3 network sources + 1 synthetic S4 for receiver scaling observation. */
    val fourSourceScalingFixtures: List<SourceFixture> =
        threeSourceFixtures + syntheticSource4Fixture

    /** 3 network + 5 synthetic for 8-source receiver scaling observation. */
    val eightSourceScalingFixtures: List<SourceFixture> =
        threeSourceFixtures + syntheticScalingFixtures.take(5)

    /** 3 network + 7 synthetic for 10-source receiver scaling (MaxJitterSources=10). */
    val tenSourceScalingFixtures: List<SourceFixture> =
        threeSourceFixtures + syntheticScalingFixtures

    fun installAuthority(
        store: AuthorityFactStore,
        fixtures: List<SourceFixture>,
        mediaKeyEpoch: Long = 1L,
    ) {
        store.acceptVerifiedKey(
            MediaKeyContextFact(
                mediaKeyEpoch = mediaKeyEpoch,
                masterKey = masterKey.copyOf(),
                masterSalt = masterSalt.copyOf(),
                keyContextHint64 = keyContextHint64.copyOf(),
            ),
        )
        for (fixture in fixtures) {
            store.acceptVerifiedSource(
                SourceAuthorizationFact(
                    sourceIdentity = fixture.sourceIdentity,
                    incarnationId = fixture.incarnationId,
                    ssrc = fixture.ssrc,
                    sourceAdmissionKey48 = fixture.sourceAdmissionKey48.copyOf(),
                    mediaKeyEpoch = mediaKeyEpoch,
                ),
            )
        }
    }

    fun buildProtectedPacket(
        fixture: SourceFixture,
        mediaSlot: Int,
        opusPayload: ByteArray,
        roc: Int = 0,
        audioLevel: Int = fixture.audioLevel,
    ): ByteArray {
        require(opusPayload.size <= ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS)
        val header =
            buildHeaderHe(
                ssrc = fixture.ssrc,
                seq = mediaSlot,
                sourceAdmissionKey48 = fixture.sourceAdmissionKey48,
                voiceActiveAudioLevel = 0x80 or (audioLevel and 0x7F),
            )
        return when (
            val result =
                ConferenceWireEgress.protect(
                    headerAndHe = header,
                    plaintextPayload = opusPayload,
                    masterKey = masterKey,
                    masterSalt = masterSalt,
                    ssrc = fixture.ssrc,
                    roc = roc,
                    seq = mediaSlot,
                )
        ) {
            is ConferenceWireEgress.EgressResult.Protected -> result.udpPayload.copyOf()
            is ConferenceWireEgress.EgressResult.Rejected ->
                error("protect failed: ${result.frozenClass} ${result.reason}")
        }
    }

    fun buildToneProtectedPacket(
        fixture: SourceFixture,
        mediaSlot: Int,
    ): ByteArray = buildProtectedPacket(fixture, mediaSlot, OpusTestVectors.encodeTone(fixture.frequencyHz))

    fun buildHeaderHe(
        ssrc: Int,
        seq: Int,
        sourceAdmissionKey48: ByteArray,
        voiceActiveAudioLevel: Int,
    ): ByteArray {
        require(sourceAdmissionKey48.size == 6)
        val header = SourceScopedSrtpEgress.buildHeaderHeTemplate(ssrc)
        header[2] = ((seq shr 8) and 0xff).toByte()
        header[3] = (seq and 0xff).toByte()
        // Profile 02 Q3: packetKey = senderDiscriminator16 || sourceDiscriminator32
        System.arraycopy(sourceAdmissionKey48, 0, header, 29, 2)
        System.arraycopy(sourceAdmissionKey48, 2, header, 25, 4)
        header[31] = voiceActiveAudioLevel.toByte()
        return header
    }

    fun egressFor(fixture: SourceFixture, initialSeq: Int): SourceScopedSrtpEgress =
        SourceScopedSrtpEgress(
            sourceIdentity = fixture.sourceIdentity,
            masterKey = masterKey.copyOf(),
            masterSalt = masterSalt.copyOf(),
            ssrc = fixture.ssrc,
            roc = 0,
            initialSeq = initialSeq,
            headerHeTemplate =
                buildHeaderHe(
                    ssrc = fixture.ssrc,
                    seq = initialSeq,
                    sourceAdmissionKey48 = fixture.sourceAdmissionKey48,
                    voiceActiveAudioLevel = 0x80 or (fixture.audioLevel and 0x7F),
                ),
        )

    fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
