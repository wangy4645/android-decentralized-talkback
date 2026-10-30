package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR-PA-SR-B0 — SOURCE signer/sender authority fence (T1–T5).
 */
class Profile01SourceSignerSenderFenceTest {
    private lateinit var validator: Profile01ConferenceMediaFactValidator
    private lateinit var ingress: Profile01ConferenceMediaFactIngress
    private lateinit var registry: ConferenceSessionMediaControlFactRegistry

    @Before
    fun setUp() {
        registry = ConferenceSessionMediaControlFactRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        validator =
            Profile01ConferenceMediaFactValidator(Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary())
        ingress = Profile01ConferenceMediaFactIngress(validator, bridge)
        seedCreationAndMembership()
    }

    private fun seedCreationAndMembership() {
        val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            ingress.ingestSession(sessionWire, Slice4MulticastNetworkConstants.DEFAULT_IFACE).publishOutcome,
        )
        val membershipResult = ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(membershipResult is Profile01MembershipIngressResult.Converged)
    }

    private fun goldenMemberWire(): Profile01WireMemberSourceFact =
        Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact()

    /** T1 — signer=A, sender=A → ACCEPT */
    @Test
    fun t1_selfOrigin_signerMatchesSender_accepts() {
        val wire = goldenMemberWire()
        assertEquals("M02", wire.moduleId)

        val result = validator.validateMember(wire)
        assertTrue(result is Profile01ValidationResult.ReadyMember)

        val publish = ingress.ingestMember(wire)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, publish.publishOutcome)
    }

    /** T2 — signer=A, sender=B → SIGNER_SENDER_MISMATCH */
    @Test
    fun t2_signerSenderMismatch_signerA_senderB_rejects() {
        val wire = goldenMemberWire() // sender M02
        val trust =
            AuthenticatedSignerOverrideTrustBoundary(
                Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary(),
                authenticatedSignerModuleId = "M01",
            )
        val localValidator = Profile01ConferenceMediaFactValidator(trust)

        val result = localValidator.validateMember(wire)
        assertTrue(result is Profile01ValidationResult.Invalid)
        assertEquals(
            Profile01SourceValidationReasons.SIGNER_SENDER_MISMATCH,
            (result as Profile01ValidationResult.Invalid).reason,
        )
    }

    /** T3 — signer=B, sender=A → SIGNER_SENDER_MISMATCH */
    @Test
    fun t3_signerSenderMismatch_signerB_senderA_rejects() {
        val wire = goldenMemberWire().copy(moduleId = "M01")
        val trust =
            AuthenticatedSignerOverrideTrustBoundary(
                Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary(),
                authenticatedSignerModuleId = "M02",
            )
        val localValidator = Profile01ConferenceMediaFactValidator(trust)

        val result = localValidator.validateMember(wire)
        assertTrue(result is Profile01ValidationResult.Invalid)
        assertEquals(
            Profile01SourceValidationReasons.SIGNER_SENDER_MISMATCH,
            (result as Profile01ValidationResult.Invalid).reason,
        )
    }

    /** T4 — unknown signer → existing trust rejection (not mismatch) */
    @Test
    fun t4_unknownSigner_preservesTrustRejection() {
        val emptyTrust =
            Profile01GoldenVectorSignedFactTrustBoundary(
                keysByModuleAndVersion = emptyMap(),
            )
        val localValidator = Profile01ConferenceMediaFactValidator(emptyTrust)
        val wire = goldenMemberWire()

        val result = localValidator.validateMember(wire)
        assertTrue(result is Profile01ValidationResult.Invalid)
        assertEquals("UNKNOWN_SIGNER_KEY", (result as Profile01ValidationResult.Invalid).reason)
    }

    /** T5 — existing SOURCE semantic validation unchanged (stale generation) */
    @Test
    fun t5_staleSourceGeneration_preservesExistingSemanticValidation() {
        val current = goldenMemberWire().copy(sourceGeneration = 2L)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, ingress.ingestMember(current).publishOutcome)

        val stale = goldenMemberWire()
        val staleResult = ingress.ingestMember(stale)
        assertNull(staleResult.publishOutcome)
        assertTrue(staleResult.validation is Profile01ValidationResult.NotPublished)
        assertEquals(
            "STALE_SOURCE_GENERATION",
            (staleResult.validation as Profile01ValidationResult.NotPublished).reason,
        )
    }

    @Test
    fun ingestSourceDeclaration_signedFactBytes_honestGolden_accepts() {
        val result =
            ingress.ingestSourceDeclarationSignedFact(
                Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes,
            )
        assertTrue(result.decode is Profile01SignedFactDecodeResult.Ready)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, result.ingress?.publishOutcome)
        assertEquals(
            "M02",
            registry.member(Profile01GoldenVectorWireFixtures.CONFERENCE_ID, "M02")?.moduleId,
        )
    }

    /**
     * Test harness: crypto verify succeeds via [inner], but reports a fixed authenticated signer id
     * for semantic fence tests (T2/T3).
     */
    private class AuthenticatedSignerOverrideTrustBoundary(
        private val inner: Profile01WireTrustBoundary,
        private val authenticatedSignerModuleId: String,
    ) : Profile01WireTrustBoundary {
        override fun verifySignedFact(signedFactBytes: ByteArray): Profile01WireVerificationResult =
            when (val result = inner.verifySignedFact(signedFactBytes)) {
                is Profile01WireVerificationResult.Rejected -> result
                is Profile01WireVerificationResult.Verified ->
                    Profile01WireVerificationResult.Verified(
                        factDigest = result.factDigest.copyOf(),
                        authenticatedSignerModuleId = authenticatedSignerModuleId,
                    )
            }
    }
}
