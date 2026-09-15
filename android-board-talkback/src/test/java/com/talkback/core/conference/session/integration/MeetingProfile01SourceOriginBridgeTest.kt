package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01WireTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01WireVerificationResult
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactVerifier
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-PA-SR-B1 — SOURCE_DECLARATION origin/publication (B1-1 … B1-7).
 */
class MeetingProfile01SourceOriginBridgeTest {
    @Test
    fun b1_1_selfOrigin_validSignedArtifact() {
        val harness = newHarness(sessionId = "b1-1", localModuleId = HOST)
        seedCreation(harness)

        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        val build =
            harness.sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = harness.sessionId,
                localModuleId = HOST,
                authoritySourceGeneration = commitment.sourceGeneration,
                ssrc = commitment.ssrc,
                sourceInstanceId = commitment.sourceInstanceId,
                mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
            )
        assertEquals(SourceOriginBuildOutcome.BUILT, build)

        val signed = harness.sourceBridge.readSignedSourceFact(harness.sessionId)
        assertNotNull(signed)
        assertEquals(Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION, factType(signed!!))
        val decoded =
            Profile01WireCborDecoder.decodeSourceDeclarationMember(signed)
        assertTrue(decoded is Profile01WireCborDecoder.DecodeResult.Ready)
        val wire = (decoded as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals(HOST, wire.moduleId)

        val ingressResult = harness.ingress.ingestSourceDeclarationSignedFact(signed)
        assertEquals(
            com.talkback.core.conference.session.ControlFactPublishOutcome.ACCEPTED,
            ingressResult.ingress?.publishOutcome,
        )
    }

    @Test
    fun b1_3_sameCommittedSource_stableIdentityAndGeneration() {
        val harness = newHarness(sessionId = "b1-3", localModuleId = HOST)
        seedCreation(harness)
        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)

        harness.sourceBridge.onLocalConferenceSourceCommitted(
            sessionId = harness.sessionId,
            localModuleId = HOST,
            authoritySourceGeneration = commitment.sourceGeneration,
            ssrc = commitment.ssrc,
            sourceInstanceId = commitment.sourceInstanceId,
            mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
        )
        val first = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!

        val secondBuild =
            harness.sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = harness.sessionId,
                localModuleId = HOST,
                authoritySourceGeneration = commitment.sourceGeneration,
                ssrc = commitment.ssrc,
                sourceInstanceId = commitment.sourceInstanceId,
                mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
            )
        assertEquals(SourceOriginBuildOutcome.ALREADY_BUILT, secondBuild)
        val second = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!
        assertArrayEquals(first, second)

        val sends = mutableListOf<ByteArray>()
        harness.sourceBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, bytes ->
                sends += bytes.copyOf()
                true
            },
        )
        harness.sourceBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, bytes ->
                sends += bytes.copyOf()
                true
            },
        )
        assertEquals(1, sends.size)
        val wire =
            (
                Profile01WireCborDecoder.decodeSourceDeclarationMember(sends.single())
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertEquals(1L, wire.sourceGeneration)
    }

    @Test
    fun b1_4_replacementSource_bumpsGenerationAndArtifact() {
        val harness = newHarness(sessionId = "b1-4", localModuleId = HOST)
        seedCreation(harness)
        val firstCommitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        harness.sourceBridge.onLocalConferenceSourceCommitted(
            sessionId = harness.sessionId,
            localModuleId = HOST,
            authoritySourceGeneration = firstCommitment.sourceGeneration,
            ssrc = firstCommitment.ssrc,
            sourceInstanceId = firstCommitment.sourceInstanceId,
            mediaGroupDescriptorDigest = firstCommitment.mediaGroupDescriptorDigest,
        )
        val firstBytes = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!

        val replacement =
            harness.localSourceAuthority.commitLocalSource(
                sessionId = harness.sessionId,
                moduleId = HOST,
                mediaKeyEpoch = 1L,
                ssrc = firstCommitment.ssrc + 1,
                sourceInstanceId = ByteArray(16) { (it + 7).toByte() },
            )
        assertEquals(2L, replacement.sourceGeneration)
        val build =
            harness.sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = harness.sessionId,
                localModuleId = HOST,
                authoritySourceGeneration = replacement.sourceGeneration,
                ssrc = replacement.ssrc,
                sourceInstanceId = replacement.sourceInstanceId,
                mediaGroupDescriptorDigest = replacement.mediaGroupDescriptorDigest,
            )
        assertEquals(SourceOriginBuildOutcome.BUILT, build)
        val secondBytes = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!
        assertTrue(!firstBytes.contentEquals(secondBytes))
        val wire =
            (
                Profile01WireCborDecoder.decodeSourceDeclarationMember(secondBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertEquals(2L, wire.sourceGeneration)
    }

    @Test
    fun b1_6_postBindRepublication_byteIdenticalSignedBytes() {
        val harness = newHarness(sessionId = "b1-6", localModuleId = HOST)
        seedCreation(harness)
        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        harness.sourceBridge.onLocalConferenceSourceCommitted(
            sessionId = harness.sessionId,
            localModuleId = HOST,
            authoritySourceGeneration = commitment.sourceGeneration,
            ssrc = commitment.ssrc,
            sourceInstanceId = commitment.sourceInstanceId,
            mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
        )
        val cached = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!

        val peerSends = mutableMapOf<String, ByteArray>()
        harness.sourceBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.trioSnapshot(),
            localModuleId = HOST,
            eligiblePeerModuleIds = setOf("M02", "M03"),
            publishToPeer = { peer, bytes ->
                peerSends[peer] = bytes.copyOf()
                true
            },
        )
        assertEquals(2, peerSends.size)
        assertArrayEquals(cached, peerSends["M02"])
        assertArrayEquals(cached, peerSends["M03"])
        assertArrayEquals(peerSends["M02"], peerSends["M03"])
    }

    @Test
    fun b1_7_peerIngressApplied_populatesRegistryMemberBinding() {
        val hostHarness = newHarness(sessionId = "b1-7-host", localModuleId = HOST)
        seedCreation(hostHarness)
        val commitment = hostHarness.localSourceAuthority.commitLocalSource(hostHarness.sessionId, HOST, mediaKeyEpoch = 1L)
        hostHarness.sourceBridge.onLocalConferenceSourceCommitted(
            sessionId = hostHarness.sessionId,
            localModuleId = HOST,
            authoritySourceGeneration = commitment.sourceGeneration,
            ssrc = commitment.ssrc,
            sourceInstanceId = commitment.sourceInstanceId,
            mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
        )
        val signed = hostHarness.sourceBridge.readSignedSourceFact(hostHarness.sessionId)!!

        val peerRegistry = ConferenceSessionMediaControlFactRegistry()
        val peerBridge = ConferenceSessionMediaGbcPublisherBridge(peerRegistry)
        val peerIngress =
            Profile01ConferenceMediaFactIngress(
                validator =
                    Profile01ConferenceMediaFactValidator(
                        TestModuleTrustBoundary(hostHarness.signerFixture),
                    ),
                publisherBridge = peerBridge,
            )
        val peerSessionIndex = MeetingProfile01ConferenceSessionIndex()
        peerSessionIndex.registerSession(hostHarness.sessionId, "CH-B1-7", rosterEpoch = 1L)
        peerSessionIndex.bindConferenceId(hostHarness.sessionId, hostHarness.sessionIndex.ensureConferenceIdHex(hostHarness.sessionId))
        val factPort =
            MeetingProfile01AwareConferenceSessionMediaFactPort(peerRegistry, peerSessionIndex)

        peerIngress.ingestCreationSignedFact(
            hostHarness.creationBridge.readSignedCreationFact(hostHarness.sessionId)!!,
            com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement(
                channelId = "CH-B1-7",
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            ),
            Slice4MulticastNetworkConstants.DEFAULT_IFACE,
        )
        assertNull(factPort.memberBinding(hostHarness.sessionId, HOST))

        val wireIngress =
            MeetingProfile01FactWireIngress(
                ingress = peerIngress,
                supplementRegistry = Profile01SessionMediaSupplementRegistry(),
                sessionIndex = peerSessionIndex,
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                incompleteApplyContinuation = MeetingProfile01IncompleteApplyContinuation(),
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { "M02" },
                localEstablishmentKeyVersion = { 1L },
            )
        val outcome = wireIngress.onConferenceSignedFact(signedFactEnvelope(signed, hostHarness.sessionId))
        assertEquals(WireIngressOutcome.APPLIED, outcome)
        assertNotNull(factPort.memberBinding(hostHarness.sessionId, HOST))
    }

    private fun signedFactEnvelope(
        signedBytes: ByteArray,
        sessionId: String,
    ): com.talkback.core.model.SignalEnvelope {
        val payload = java.util.Base64.getEncoder().encodeToString(signedBytes)
        return com.talkback.core.model.SignalEnvelope(
            type = com.talkback.core.model.SignalType.CONFERENCE_SIGNED_FACT,
            from = com.talkback.core.model.EndpointAddress(com.talkback.core.model.ModuleId(HOST), com.talkback.core.model.EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = payload,
            nonce = "n1",
            signature = "s1",
        )
    }

    private fun factType(signedBytes: ByteArray): Int? = Profile01WireCborDecoder.readFactType(signedBytes)

    private fun seedCreation(harness: Harness) {
        harness.creationBridge.onTopologyPublished(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )
        val signed = harness.creationBridge.readSignedCreationFact(harness.sessionId)!!
        harness.ingress.ingestCreationSignedFact(
            signed,
            com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement(
                channelId = "CH-B1",
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            ),
            Slice4MulticastNetworkConstants.DEFAULT_IFACE,
        )
    }

    private fun newHarness(
        sessionId: String,
        localModuleId: String,
    ): Harness {
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, "CH-B1", rosterEpoch = 1L)
        val mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val registry = ConferenceSessionMediaControlFactRegistry()
        val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val signerFixture = testSignerFixture(localModuleId)
        val validator = Profile01ConferenceMediaFactValidator(TestModuleTrustBoundary(signerFixture))
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge = publisherBridge,
                supplementRegistry = supplementRegistry,
            )
        val creationBridge =
            MeetingProfile01CreationOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                publisher = MeetingProfile01CreationOriginPublisher(signerFixture.signer),
                membershipConvergence = ingress.membershipRegistry(),
            )
        val sourceBridge =
            MeetingProfile01SourceOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                supplementRegistry = supplementRegistry,
                membershipConvergence = ingress.membershipRegistry(),
                publisher = MeetingProfile01SourceOriginPublisher(signerFixture.signer),
            )
        return Harness(
            sessionId = sessionId,
            localModuleId = localModuleId,
            sessionIndex = sessionIndex,
            creationBridge = creationBridge,
            sourceBridge = sourceBridge,
            ingress = ingress,
            localSourceAuthority = Profile01LocalConferenceSourceIdentityAuthority(),
            signerFixture = signerFixture,
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

    private class Harness(
        val sessionId: String,
        val localModuleId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val sourceBridge: MeetingProfile01SourceOriginBridge,
        val ingress: Profile01ConferenceMediaFactIngress,
        val localSourceAuthority: Profile01LocalConferenceSourceIdentityAuthority,
        val signerFixture: SignerFixture,
    ) {
        fun duoSnapshot(): ConferenceTopologySnapshot =
            ConferenceTopologySnapshot(
                conferenceId = sessionId,
                rosterEpoch = 1L,
                anchorEpoch = 100L,
                anchorId = HOST,
                meshGeneration = 7L,
                topologyMode = ConferenceTopologyMode.ANCHOR,
                hostModuleId = HOST,
                members = listOf(HOST, "M02"),
                actualMediaEdges = emptySet(),
            )

        fun trioSnapshot(): ConferenceTopologySnapshot =
            duoSnapshot().copy(members = listOf(HOST, "M02", "M03"))
    }

    companion object {
        private const val HOST = "M01"
    }
}
