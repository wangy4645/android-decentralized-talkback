package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorWireFixtures
import com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult
import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import com.talkback.core.conference.session.profile01.Profile01ValidationResult
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipIncarnationAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelopeBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01SourceDeclarationAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborEncoder
import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2e — field-isomorphic desk for M04 ingress validation reason @10:29:20.567.
 *
 * Evidence: `talkback/logs/issue-a-final-closure-field-20260923-102355/`
 * session `94597c62-dc3d-4a2c-a719-50dfc0d9710b`.
 *
 * Field context frozen:
 * - M01 host; M02/M03 catalog already installed
 * - membershipVersion=1 mediaKeyEpoch=2
 * - M04 authoritative source gen=2 ssrc=1071254848
 * - topology/planned roster included M04; validator authority roster did not
 */
class Profile01A2eM04IngressValidationDeskTest {
    private val membershipWire = Profile01GoldenVectorWireFixtures.decodeMembershipWireFact()

    // Field session `94597c62…`; desk uses golden conference id for production wire fixtures.
    private val fieldConferenceIdHex = Profile01GoldenVectorWireFixtures.CONFERENCE_ID
    private val fieldConferenceEpoch = membershipWire.conferenceEpoch
    private val fieldMembershipVersion = 1L
    private val fieldMediaKeyEpoch = 2L

    private val fieldM04SourceGeneration = 2L
    private val fieldM04Ssrc = 1_071_254_848
    private val fieldM03SourceGeneration = 2L
    private val fieldM03Ssrc = 1_933_820_532

    private val fieldPlannedRoster = listOf("M01", "M02", "M03", "M04")

    private val conferenceIdBytes = fieldConferenceIdHex.hexToId128Bytes()

    private lateinit var validator: Profile01ConferenceMediaFactValidator
    private lateinit var signers: Map<String, ModuleSigner>

    @Before
    fun setUp() {
        signers =
            mapOf(
                "M03" to testSigner("M03"),
                "M04" to testSigner("M04"),
            )
        validator =
            Profile01ConferenceMediaFactValidator(
                Profile01GoldenVectorSignedFactTrustBoundary(
                    buildMap {
                        putAll(goldenPublicKeys())
                        signers.values.forEach { putAll(it.publicKeys) }
                    },
                ),
            )
    }

    /**
     * FIELD-ISOMORPHIC DESK RED:
     * validateMember(M04 gen2) → Invalid(MEMBER_NOT_IN_ROSTER)
     * authority head roster={M01,M02,M03}; planned roster={M01,M02,M03,M04}
     */
    @Test
    fun a2e_fieldIsomorphic_m04Gen2RejectedWithMemberNotInRoster() {
        val head = bootstrapFieldMomentMembershipHead()
        assertInstalledControlPeerPasses(head)

        val m04Signed =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                membershipVersion = fieldMembershipVersion,
                mediaKeyEpoch = fieldMediaKeyEpoch,
                conferenceEpoch = fieldConferenceEpoch,
            )
        val wire = decodeMemberWire(m04Signed)
        val validation = validator.validateMember(wire)

        assertFalse(
            "planned roster must include M04 (field topology) but authority head must not",
            head.hasMember("M04"),
        )
        assertEquals(fieldPlannedRoster.sorted(), fieldPlannedRoster.distinct().sorted())
        assertEquals(
            listOf("M01", "M02", "M03"),
            head.members.map { it.moduleId }.sorted(),
        )
        assertEquals(
            MeetingProfile01PeerSourceDeclarationContinuity.MEMBER_NOT_IN_ROSTER,
            rejectionReason(validation),
        )
        assertTrue(validation is Profile01ValidationResult.Invalid)
    }

    @Test
    fun a2e_control_m03Gen2ReadyAtSameHead() {
        val head = bootstrapFieldMomentMembershipHead()
        assertInstalledControlPeerPasses(head)

        val m03Signed =
            signedSourceDeclaration(
                moduleId = "M03",
                sourceGeneration = fieldM03SourceGeneration,
                ssrc = fieldM03Ssrc,
                membershipVersion = fieldMembershipVersion,
                mediaKeyEpoch = fieldMediaKeyEpoch,
                conferenceEpoch = fieldConferenceEpoch,
            )
        assertTrue(
            validator.validateMember(decodeMemberWire(m03Signed))
                is Profile01ValidationResult.ReadyMember,
        )
    }

    @Test
    fun a2e_counterfactual_headIncludingM04_readyMember() {
        val head = bootstrapFieldMomentMembershipHead()
        val withM04 = applyMembershipIncludingM04(head)

        val m04Signed =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                membershipVersion = withM04.membershipVersion,
                mediaKeyEpoch = withM04.mediaKeyEpoch,
                conferenceEpoch = fieldConferenceEpoch,
            )
        assertTrue(
            validator.validateMember(decodeMemberWire(m04Signed))
                is Profile01ValidationResult.ReadyMember,
        )
    }

    private fun assertInstalledControlPeerPasses(head: Profile01MembershipGeneration) {
        assertTrue(head.hasMember("M03"))
        val m03Signed =
            signedSourceDeclaration(
                moduleId = "M03",
                sourceGeneration = fieldM03SourceGeneration,
                ssrc = fieldM03Ssrc,
                membershipVersion = fieldMembershipVersion,
                mediaKeyEpoch = fieldMediaKeyEpoch,
                conferenceEpoch = fieldConferenceEpoch,
            )
        assertTrue(
            validator.validateMember(decodeMemberWire(m03Signed))
                is Profile01ValidationResult.ReadyMember,
        )
    }

    /**
     * M01 @10:29:20 field moment: memV=1 mke=2, catalog peers M02/M03 installed,
     * authoritative membership head converged from host CREATION+MEMBERSHIP without M04.
     */
    private fun bootstrapFieldMomentMembershipHead(): Profile01MembershipGeneration {
        val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        when (val seeded = validator.membershipRegistry().seedFromCreation(sessionWire)) {
            is Profile01MembershipApplyResult.Accepted -> Unit
            is Profile01MembershipApplyResult.Idempotent -> Unit
            else -> error("creation seed failed: $seeded")
        }
        when (
            val applied =
                validator.membershipRegistry().applyMembership(
                    Profile01GoldenVectorWireFixtures.decodeMembershipWireFact(),
                )
        ) {
            is Profile01MembershipApplyResult.Accepted -> return applied.generation
            is Profile01MembershipApplyResult.Idempotent ->
                return validator.membershipRegistry().current(sessionWire.conferenceId)!!
            else -> error("membership apply failed: $applied")
        }
    }

    private fun applyMembershipIncludingM04(
        current: Profile01MembershipGeneration,
    ): Profile01MembershipGeneration {
        val bumpedMembership =
            com.talkback.core.conference.session.profile01.Profile01WireMembershipFact(
                signedFactBytes = byteArrayOf(),
                factDigest = ByteArray(32) { (it + 0xA4).toByte() },
                conferenceId = fieldConferenceIdHex,
                conferenceEpoch = current.conferenceEpoch,
                ownerModuleId = membershipWire.ownerModuleId,
                membershipVersion = current.membershipVersion + 1L,
                previousMembershipDigest = current.generationFactDigest.copyOf(),
                members =
                    current.members +
                        Profile01WireMembershipMember(
                            moduleId = "M04",
                            membershipIncarnationId =
                                Profile01MembershipIncarnationAuthority.deriveIncarnationId128(
                                    conferenceIdBytes,
                                    "M04",
                                ),
                        ),
                mediaKeyEpoch = current.mediaKeyEpoch,
                mediaKeyCommitment = current.mediaKeyCommitment.copyOf(),
            )
        val applied = validator.membershipRegistry().applyMembership(bumpedMembership)
        assertTrue(applied is Profile01MembershipApplyResult.Accepted)
        return (applied as Profile01MembershipApplyResult.Accepted).generation
    }

    private fun signedSourceDeclaration(
        moduleId: String,
        sourceGeneration: Long,
        ssrc: Int,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
        conferenceEpoch: Long,
    ): ByteArray {
        val signer = signers.getValue(moduleId)
        val snapshot =
            Profile01SourceDeclarationAuthoritySnapshot(
                conferenceId = conferenceIdBytes,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                declaringModuleId = moduleId,
                sourceGeneration = sourceGeneration,
                sourceInstanceId =
                    Profile01MembershipIncarnationAuthority.deriveIncarnationId128(
                        conferenceIdBytes,
                        moduleId,
                    ),
                ssrc = ssrc,
                mediaGroupDescriptorDigest = goldenDescriptorDigest(),
                signerKeyVersion = 1L,
            )
        val full = Profile01WireCborEncoder.encodeSourceDeclarationFullFact(snapshot)
        return Profile01SignedFactEnvelopeBuilder.build(full, signer.signer.signFullFact(full))
    }

    private fun decodeMemberWire(signedBytes: ByteArray) =
        (
            Profile01WireCborDecoder.decodeSourceDeclarationMember(signedBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready
        ).value

    private fun goldenDescriptorDigest(): ByteArray {
        val envelope =
            Profile01WireCborDecoder.parseEnvelope(
                Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes,
            )!!
        val authority =
            com.talkback.core.conference.session.profile01.wire.Profile01CborCodec
                .decodeStrict(envelope.fullCanonicalBytes)
                .intKeyMap()!![2]!!
                .intKeyMap()!!
        return authority[9]!!.asByteString()!!.copyOf()
    }

    private fun goldenPublicKeys(): Map<Pair<String, Long>, ByteArray> =
        mapOf(
            "M01" to 1L to hex(GOLDEN_M01_PUBLIC_X963_HEX),
            "M02" to 1L to hex(GOLDEN_M02_PUBLIC_X963_HEX),
        )

    private fun rejectionReason(validation: Profile01ValidationResult): String =
        when (validation) {
            is Profile01ValidationResult.Invalid -> validation.reason
            is Profile01ValidationResult.NotPublished -> validation.reason
            is Profile01ValidationResult.ReadyMember -> error("expected rejection, got ReadyMember")
            else -> error("unexpected validation: $validation")
        }

    private fun testSigner(moduleId: String): ModuleSigner {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        return ModuleSigner(
            signer =
                Profile01PersistedSignedFactSigner.fromPkcs8(
                    pkcs8PrivateKey = keyPair.private.encoded,
                    signerModuleId = moduleId,
                    signerKeyVersion = 1L,
                )!!,
            publicKeys = mapOf(moduleId to 1L to ecPublicKeyX963(keyPair.public)),
        )
    }

    private fun ecPublicKeyX963(publicKey: java.security.PublicKey): ByteArray {
        val ec = publicKey as ECPublicKey
        val x = ec.w.affineX.toByteArray()
        val y = ec.w.affineY.toByteArray()
        val x32 = x.copyOfRange(maxOf(0, x.size - 32), x.size)
        val y32 = y.copyOfRange(maxOf(0, y.size - 32), y.size)
        return byteArrayOf(0x04) + ByteArray(32 - x32.size) + x32 + ByteArray(32 - y32.size) + y32
    }

    private fun hex(value: String): ByteArray {
        val clean = value.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
        }
        return out
    }

    private data class ModuleSigner(
        val signer: Profile01PersistedSignedFactSigner,
        val publicKeys: Map<Pair<String, Long>, ByteArray>,
    )

    companion object {
        private const val GOLDEN_M01_PUBLIC_X963_HEX =
            "04984225585d2285c138033d6140e3cef8b91859704e53c313f8b636ba4f9676499734144f46fd19a767a545287c4396b97b69dd38faaea8981adc1a4fed9b401e"
        private const val GOLDEN_M02_PUBLIC_X963_HEX =
            "04cbbcaf7e67f163b45f766bed945666932e4629e1e228afbfc8ece2a8a8cb9eb2ae522dd528a32b21a611569f914b5a79d9afe1c8f438ba987da3037e0ec8b3e7"
    }
}
