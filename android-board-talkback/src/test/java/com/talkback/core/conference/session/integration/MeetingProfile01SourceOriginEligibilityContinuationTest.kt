package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01SignedFactDecodeResult
import com.talkback.core.conference.session.profile01.Profile01ValidationResult
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01WireTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01WireVerificationResult
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactVerifier
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.conference.session.profile01.wire.toHexLower
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-PA-SR-B1-R1 — SOURCE origin eligibility continuation (R1-1 … R1-5).
 */
class MeetingProfile01SourceOriginEligibilityContinuationTest {
    @Test
    fun r1_1_hostAuthoritativeCreationCommit_membershipVisible_sourceBuiltOnce() {
        val harness = newHostHarness(sessionId = "r1-1")
        harness.creationBridge.onTopologyPublished(
            sessionId = harness.sessionId,
            snapshot = harness.hostDuoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, _ -> true },
        )
        assertNotNull(harness.ingress.membershipRegistry().current(harness.conferenceIdHex()))
        assertNull(harness.sourceBridge.readSignedSourceFact(harness.sessionId))

        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        val build = harness.attemptSourceBuild(commitment)
        assertEquals(SourceOriginBuildOutcome.BUILT, build)
        assertNotNull(harness.sourceBridge.readSignedSourceFact(harness.sessionId))
        assertFalse(harness.sourceBridge.eligibilityObligations().hasPending(harness.sessionId, HOST, 1L))

        val second = harness.attemptSourceBuild(commitment)
        assertEquals(SourceOriginBuildOutcome.ALREADY_BUILT, second)
    }

    @Test
    fun r1_2_peerEarlySourceBlocked_creationApplied_reevaluatedBuiltOnce() {
        val hostHarness = newHostHarness(sessionId = "r1-2-host")
        hostHarness.creationBridge.onTopologyPublished(
            sessionId = hostHarness.sessionId,
            snapshot = hostHarness.hostDuoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, _ -> true },
        )
        val creationBytes = hostHarness.creationBridge.readSignedCreationFact(hostHarness.sessionId)!!
        val conferenceIdHex = hostHarness.conferenceIdHex()

        val peerHarness =
            newPeerHarness(
                sessionId = "r1-2-peer",
                hostConferenceIdHex = conferenceIdHex,
                hostSignerFixture = hostHarness.signerFixture,
            )
        peerHarness.supplementRegistry.put(
            Profile01DerivedMediaKeyMaterial(
                conferenceId = conferenceIdHex,
                conferenceEpoch = 7L,
                membershipVersion = 0L,
                mediaKeyEpoch = 1L,
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
                membershipKeyContextDigest = ByteArray(32),
            ),
        )

        val commitment = peerHarness.localSourceAuthority.commitLocalSource(peerHarness.sessionId, PEER, mediaKeyEpoch = 1L)
        val blocked = peerHarness.attemptSourceBuild(commitment)
        assertEquals(SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, blocked)
        assertTrue(peerHarness.sourceBridge.eligibilityObligations().hasPending(peerHarness.sessionId, PEER, 1L))
        assertNull(peerHarness.sourceBridge.readSignedSourceFact(peerHarness.sessionId))

        peerHarness.ingress.onCreationMembershipEstablished = null
        val supplement =
            Profile01SessionMediaSupplement(
                channelId = "CH-R1-2",
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
        val ingressResult =
            peerHarness.ingress.ingestCreationSignedFact(
                creationBytes,
                supplement,
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        assertEquals(Profile01SignedFactDecodeResult.Ready, ingressResult.decode)
        assertTrue(ingressResult.ingress?.validation is Profile01ValidationResult.ReadySession)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, ingressResult.ingress?.publishOutcome)
        val wireConferenceId =
            (
                Profile01WireCborDecoder.decodeCreationSession(creationBytes, supplement)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value.conferenceId
        assertEquals(conferenceIdHex, wireConferenceId)
        assertNotNull(peerHarness.ingress.membershipRegistry().current(wireConferenceId))
        val built = peerHarness.attemptSourceBuild(commitment, "r1b_peer_creation_applied")
        assertEquals(SourceOriginBuildOutcome.BUILT, built)
        assertNotNull(peerHarness.sourceBridge.readSignedSourceFact(peerHarness.sessionId))
        assertFalse(peerHarness.sourceBridge.eligibilityObligations().hasPending(peerHarness.sessionId, PEER, 1L))
    }

    @Test
    fun r1_3_duplicateEligibilityEvents_noDuplicateFirstBuild() {
        val harness = newHostHarness(sessionId = "r1-3")
        harness.creationBridge.onTopologyPublished(
            sessionId = harness.sessionId,
            snapshot = harness.hostDuoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, _ -> true },
        )
        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)

        repeat(3) {
            val outcome = harness.attemptSourceBuild(commitment, "r1a_host_creation_projected")
            assertEquals(if (it == 0) SourceOriginBuildOutcome.BUILT else SourceOriginBuildOutcome.ALREADY_BUILT, outcome)
        }

        val signed = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!
        val wire =
            (
                Profile01WireCborDecoder.decodeSourceDeclarationMember(signed)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertEquals(1L, wire.sourceGeneration)
    }

    @Test
    fun r1_4_staleSessionSourceIdentity_obligationDiscarded() {
        val harness = newHostHarness(sessionId = "r1-4")
        harness.sessionIndex.ensureConferenceIdHex(harness.sessionId)
        val firstCommitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        val blocked = harness.attemptSourceBuild(firstCommitment)
        assertEquals(SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, blocked)
        assertTrue(
            harness.sourceBridge.eligibilityObligations()
                .hasPending(harness.sessionId, HOST, firstCommitment.sourceGeneration),
        )

        harness.creationBridge.onTopologyPublished(
            sessionId = harness.sessionId,
            snapshot = harness.hostDuoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, _ -> true },
        )

        val replacement =
            harness.localSourceAuthority.commitLocalSource(
                sessionId = harness.sessionId,
                moduleId = HOST,
                mediaKeyEpoch = 1L,
                ssrc = firstCommitment.ssrc + 1,
                sourceInstanceId = ByteArray(16) { (it + 3).toByte() },
            )
        assertEquals(2L, replacement.sourceGeneration)
        val build = harness.attemptSourceBuild(replacement)
        assertEquals(SourceOriginBuildOutcome.BUILT, build)
        assertFalse(
            harness.sourceBridge.eligibilityObligations()
                .hasPending(harness.sessionId, HOST, firstCommitment.sourceGeneration),
        )
    }

    @Test
    fun r1_5_onceBuilt_byteIdenticalRepublicationUnchanged() {
        val harness = newHostHarness(sessionId = "r1-5")
        harness.creationBridge.onTopologyPublished(
            sessionId = harness.sessionId,
            snapshot = harness.hostDuoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, _ -> true },
        )
        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        assertEquals(SourceOriginBuildOutcome.BUILT, harness.attemptSourceBuild(commitment))
        val cached = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!

        val peerSends = mutableListOf<ByteArray>()
        repeat(2) {
            harness.sourceBridge.onEligiblePeers(
                sessionId = harness.sessionId,
                snapshot = harness.hostDuoSnapshot(),
                localModuleId = HOST,
                eligiblePeerModuleIds = setOf(PEER),
                publishToPeer = { _, bytes ->
                    peerSends += bytes.copyOf()
                    true
                },
            )
        }
        assertEquals(1, peerSends.size)
        assertArrayEquals(cached, peerSends.single())
    }

    private fun newHostHarness(sessionId: String): Harness =
        newHarness(sessionId = sessionId, localModuleId = HOST, channelId = "CH-R1-HOST")

    private fun newPeerHarness(
        sessionId: String,
        hostConferenceIdHex: String,
        hostSignerFixture: SignerFixture,
    ): Harness {
        val harness = newHarness(
            sessionId = sessionId,
            localModuleId = PEER,
            channelId = "CH-R1-PEER",
            ingressTrustFixture = hostSignerFixture,
        )
        harness.sessionIndex.bindConferenceId(sessionId, hostConferenceIdHex)
        return harness
    }

    private fun newHarness(
        sessionId: String,
        localModuleId: String,
        channelId: String,
        ingressTrustFixture: SignerFixture? = null,
        creationSignerFixture: SignerFixture? = null,
    ): Harness {
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 1L)
        val mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val registry = ConferenceSessionMediaControlFactRegistry()
        val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val signerFixture = testSignerFixture(localModuleId)
        val trustFixture = ingressTrustFixture ?: signerFixture
        val validator = Profile01ConferenceMediaFactValidator(TestModuleTrustBoundary(trustFixture))
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge = publisherBridge,
                supplementRegistry = supplementRegistry,
            )
        val localSourceAuthority = Profile01LocalConferenceSourceIdentityAuthority()
        val sourceBridge =
            MeetingProfile01SourceOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                supplementRegistry = supplementRegistry,
                membershipConvergence = ingress.membershipRegistry(),
                publisher = MeetingProfile01SourceOriginPublisher(signerFixture.signer),
            )
        ingress.onCreationMembershipEstablished = { conferenceId ->
            val boundSessionId = sessionIndex.sessionIdForConference(conferenceId)
            if (boundSessionId == sessionId) {
                val commitment = localSourceAuthority.commitLocalSource(sessionId, localModuleId, mediaKeyEpoch = 1L)
                sourceBridge.onLocalConferenceSourceCommitted(
                    sessionId = sessionId,
                    localModuleId = localModuleId,
                    authoritySourceGeneration = commitment.sourceGeneration,
                    ssrc = commitment.ssrc,
                    sourceInstanceId = commitment.sourceInstanceId,
                    mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
                    continuationTrigger = "r1b_peer_creation_applied",
                )
            }
        }
        val creationBridge =
            MeetingProfile01CreationOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                publisher =
                    MeetingProfile01CreationOriginPublisher(
                        (creationSignerFixture ?: signerFixture).signer,
                    ),
                membershipConvergence = ingress.membershipRegistry(),
            )
        return Harness(
            sessionId = sessionId,
            localModuleId = localModuleId,
            sessionIndex = sessionIndex,
            creationBridge = creationBridge,
            sourceBridge = sourceBridge,
            ingress = ingress,
            supplementRegistry = supplementRegistry,
            localSourceAuthority = localSourceAuthority,
            signerFixture = signerFixture,
        )
    }

    private data class Harness(
        val sessionId: String,
        val localModuleId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val sourceBridge: MeetingProfile01SourceOriginBridge,
        val ingress: Profile01ConferenceMediaFactIngress,
        val supplementRegistry: Profile01SessionMediaSupplementRegistry,
        val localSourceAuthority: Profile01LocalConferenceSourceIdentityAuthority,
        val signerFixture: SignerFixture,
    ) {
        fun conferenceIdHex(): String = sessionIndex.conferenceIdForSession(sessionId)!!

        fun hostDuoSnapshot(): ConferenceTopologySnapshot =
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

        fun attemptSourceBuild(
            commitment: Profile01LocalConferenceSourceIdentityAuthority.Commitment,
            continuationTrigger: String? = null,
        ): SourceOriginBuildOutcome =
            sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = sessionId,
                localModuleId = localModuleId,
                authoritySourceGeneration = commitment.sourceGeneration,
                ssrc = commitment.ssrc,
                sourceInstanceId = commitment.sourceInstanceId,
                mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
                continuationTrigger = continuationTrigger,
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
        val signer =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = moduleId,
                signerKeyVersion = 1L,
            )!!
        return SignerFixture(signer, keyPair.public.encoded)
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
            val digest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
            return Profile01WireVerificationResult.Verified(
                factDigest = digest.copyOf(),
                authenticatedSignerModuleId = fixture.signer.signerModuleId,
            )
        }
    }

    companion object {
        private const val HOST = "M01"
        private const val PEER = "M02"
    }
}
