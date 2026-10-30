package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01WireTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01WireVerificationResult
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactVerifier
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE-GENERATION-AUTHORITY-SPLIT — the bridge must not number incarnations.
 *
 * `Profile01LocalConferenceSourceIdentityAuthority` is the single generation authority, so
 * `wire.sourceGeneration == registry.membershipIncarnationId == authority.sourceGeneration`
 * must hold even when commitments were made while the build was in `ORIGIN_SOURCE_GAP`.
 * A second counter inside the bridge produced a permanent offset and fenced shadow TX with
 * `SOURCE_GENERATION_MISMATCH` for the whole session.
 */
class MeetingProfile01SourceGenerationAuthorityTest {
    /** Field case: authority advances during build gaps, first publication lands at gen3. */
    @Test
    fun gapsBeforeFirstPublication_publishAuthorityGenerationVerbatim() {
        val harness = newHarness("gen-auth-1")

        assertEquals(SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, harness.commitAndBuild(mediaKeyEpoch = 1L))
        assertEquals(1L, harness.authorityGeneration())
        assertEquals(SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, harness.commitAndBuild(mediaKeyEpoch = 2L))
        assertEquals(2L, harness.authorityGeneration())

        harness.seedCreation()
        assertEquals(SourceOriginBuildOutcome.BUILT, harness.commitAndBuild(mediaKeyEpoch = 3L))

        assertEquals(3L, harness.authorityGeneration())
        assertEquals(3L, harness.wireGeneration())
        harness.ingestPublishedSourceIntoRegistry(ControlFactPublishOutcome.ACCEPTED)
        assertEquals(3L, harness.registryIncarnation())
        harness.assertSingleGenerationAuthority()
    }

    /** Normal successor — epoch advance produces the next incarnation end to end. */
    @Test
    fun successorAfterEpochAdvance_carriesAuthorityGeneration() {
        val harness = newHarness("gen-auth-2")
        harness.seedCreation()
        assertEquals(SourceOriginBuildOutcome.BUILT, harness.commitAndBuild(mediaKeyEpoch = 1L))
        harness.ingestPublishedSourceIntoRegistry(ControlFactPublishOutcome.ACCEPTED)
        assertEquals(1L, harness.registryIncarnation())

        assertEquals(SourceOriginBuildOutcome.BUILT, harness.commitAndBuild(mediaKeyEpoch = 2L))

        assertEquals(2L, harness.authorityGeneration())
        assertEquals(2L, harness.wireGeneration())
        harness.ingestPublishedSourceIntoRegistry(ControlFactPublishOutcome.ACCEPTED)
        assertEquals(2L, harness.registryIncarnation())
        harness.assertSingleGenerationAuthority()
    }

    /**
     * Skipped generations are legal: an incarnation the bridge never published is still an
     * authority decision, and frozen semantics require monotonic succession, not contiguity.
     */
    @Test
    fun generationSkippedByUnpublishedCommitment_isAcceptedDownstream() {
        val harness = newHarness("gen-auth-3")
        harness.seedCreation()
        assertEquals(SourceOriginBuildOutcome.BUILT, harness.commitAndBuild(mediaKeyEpoch = 1L))
        harness.ingestPublishedSourceIntoRegistry(ControlFactPublishOutcome.ACCEPTED)
        assertEquals(1L, harness.registryIncarnation())

        // gen2 is committed but never reaches the bridge — the real ORIGIN_SOURCE_GAP shape.
        harness.commitOnly(mediaKeyEpoch = 2L)
        assertEquals(2L, harness.authorityGeneration())

        assertEquals(SourceOriginBuildOutcome.BUILT, harness.commitAndBuild(mediaKeyEpoch = 3L))

        assertEquals(3L, harness.wireGeneration())
        harness.ingestPublishedSourceIntoRegistry(ControlFactPublishOutcome.ACCEPTED)
        assertEquals(3L, harness.registryIncarnation())
        harness.assertSingleGenerationAuthority()
    }

    /** Authority is the numbering authority, but a delayed old commitment must not downgrade. */
    @Test
    fun delayedOlderCommitment_cannotDowngradePublishedGeneration() {
        val harness = newHarness("gen-auth-4")
        harness.seedCreation()
        harness.commitAndBuild(mediaKeyEpoch = 1L)
        val staleCommitment = harness.currentCommitment()
        harness.commitAndBuild(mediaKeyEpoch = 2L)
        assertEquals(SourceOriginBuildOutcome.BUILT, harness.commitAndBuild(mediaKeyEpoch = 3L))
        val publishedBytes = harness.signedSource()
        assertEquals(3L, harness.wireGeneration())

        val replay =
            harness.sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = harness.sessionId,
                localModuleId = HOST,
                authoritySourceGeneration = staleCommitment.sourceGeneration,
                ssrc = staleCommitment.ssrc,
                sourceInstanceId = staleCommitment.sourceInstanceId,
                mediaGroupDescriptorDigest = staleCommitment.mediaGroupDescriptorDigest,
            )

        assertEquals(SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, replay)
        assertArrayEquals(publishedBytes, harness.signedSource())
        assertEquals(3L, harness.wireGeneration())
        harness.ingestPublishedSourceIntoRegistry(ControlFactPublishOutcome.ACCEPTED)
        assertEquals(3L, harness.registryIncarnation())
    }

    private fun newHarness(sessionId: String): Harness {
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, CHANNEL_ID, rosterEpoch = 1L)
        val mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val registry = ConferenceSessionMediaControlFactRegistry()
        val signerFixture = testSignerFixture(HOST)
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = Profile01ConferenceMediaFactValidator(TestModuleTrustBoundary(signerFixture)),
                publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry),
                supplementRegistry = supplementRegistry,
            )
        return Harness(
            sessionId = sessionId,
            sessionIndex = sessionIndex,
            registry = registry,
            ingress = ingress,
            creationBridge =
                MeetingProfile01CreationOriginBridge(
                    sessionIndex = sessionIndex,
                    mediaKeyAuthority = mediaKeyAuthority,
                    publisher = MeetingProfile01CreationOriginPublisher(signerFixture.signer),
                    membershipConvergence = ingress.membershipRegistry(),
                ),
            sourceBridge =
                MeetingProfile01SourceOriginBridge(
                    sessionIndex = sessionIndex,
                    mediaKeyAuthority = mediaKeyAuthority,
                    supplementRegistry = supplementRegistry,
                    membershipConvergence = ingress.membershipRegistry(),
                    publisher = MeetingProfile01SourceOriginPublisher(signerFixture.signer),
                ),
            authority = Profile01LocalConferenceSourceIdentityAuthority(),
        )
    }

    private class Harness(
        val sessionId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val registry: ConferenceSessionMediaControlFactRegistry,
        val ingress: Profile01ConferenceMediaFactIngress,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val sourceBridge: MeetingProfile01SourceOriginBridge,
        val authority: Profile01LocalConferenceSourceIdentityAuthority,
    ) {
        fun seedCreation() {
            creationBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = snapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf(PEER),
                publishToPeer = { _, _ -> true },
            )
            ingress.ingestCreationSignedFact(
                creationBridge.readSignedCreationFact(sessionId)!!,
                Profile01SessionMediaSupplement(
                    channelId = CHANNEL_ID,
                    masterKey = ByteArray(16),
                    masterSalt = ByteArray(12),
                    keyContextHint64 = ByteArray(8),
                ),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        }

        fun commitOnly(mediaKeyEpoch: Long): Profile01LocalConferenceSourceIdentityAuthority.Commitment =
            authority.commitLocalSource(sessionId, HOST, mediaKeyEpoch = mediaKeyEpoch)

        fun commitAndBuild(mediaKeyEpoch: Long): SourceOriginBuildOutcome {
            val commitment = commitOnly(mediaKeyEpoch)
            return sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = sessionId,
                localModuleId = HOST,
                authoritySourceGeneration = commitment.sourceGeneration,
                ssrc = commitment.ssrc,
                sourceInstanceId = commitment.sourceInstanceId,
                mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
            )
        }

        fun currentCommitment(): Profile01LocalConferenceSourceIdentityAuthority.Commitment =
            authority.currentCommitment(sessionId, HOST)!!

        fun authorityGeneration(): Long = currentCommitment().sourceGeneration

        fun signedSource(): ByteArray = sourceBridge.readSignedSourceFact(sessionId)!!

        fun wireGeneration(): Long =
            (
                Profile01WireCborDecoder.decodeSourceDeclarationMember(signedSource())
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value.sourceGeneration

        fun ingestPublishedSourceIntoRegistry(expected: ControlFactPublishOutcome) {
            val result = ingress.ingestSourceDeclarationSignedFact(signedSource())
            assertEquals(expected, result.ingress?.publishOutcome)
        }

        fun registryIncarnation(): Long =
            registry.member(sessionIndex.ensureConferenceIdHex(sessionId), HOST)!!.membershipIncarnationId

        fun assertSingleGenerationAuthority() {
            val authorityGeneration = authorityGeneration()
            assertEquals(authorityGeneration, wireGeneration())
            assertEquals(authorityGeneration, registryIncarnation())
            assertTrue(authorityGeneration > 0L)
        }

        private fun snapshot(): ConferenceTopologySnapshot =
            ConferenceTopologySnapshot(
                conferenceId = sessionId,
                rosterEpoch = 1L,
                anchorEpoch = 100L,
                anchorId = HOST,
                meshGeneration = 7L,
                topologyMode = ConferenceTopologyMode.ANCHOR,
                hostModuleId = HOST,
                members = listOf(HOST, PEER),
                actualMediaEdges = emptySet(),
            )
    }

    private data class SignerFixture(
        val signer: Profile01PersistedSignedFactSigner,
        val publicKeySpki: ByteArray,
    )

    private fun testSignerFixture(moduleId: String): SignerFixture {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        return SignerFixture(
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = moduleId,
                signerKeyVersion = 1L,
            )!!,
            keyPair.public.encoded,
        )
    }

    private class TestModuleTrustBoundary(
        private val fixture: SignerFixture,
    ) : Profile01WireTrustBoundary {
        override fun verifySignedFact(signedFactBytes: ByteArray): Profile01WireVerificationResult {
            val envelope =
                Profile01SignedFactEnvelope.parse(signedFactBytes)
                    ?: return Profile01WireVerificationResult.Rejected("MALFORMED_SIGNED_FACT")
            val verify =
                Profile01SignedFactVerifier.verifySignature(
                    envelope.fullCanonicalBytes,
                    envelope.signatureRs,
                    fixture.publicKeySpki,
                )
            if (verify != "PASS") {
                return Profile01WireVerificationResult.Rejected(verify)
            }
            return Profile01WireVerificationResult.Verified(
                factDigest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes),
                authenticatedSignerModuleId = fixture.signer.signerModuleId,
            )
        }
    }

    private companion object {
        const val HOST = "M01"
        const val PEER = "M02"
        const val CHANNEL_ID = "CH-GEN-AUTH"
    }
}
