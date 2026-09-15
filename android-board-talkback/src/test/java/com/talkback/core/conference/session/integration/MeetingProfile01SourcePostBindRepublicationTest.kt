package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.toHexLower
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-PA-SR-B1-R2 — SOURCE post-bind republication obligation (R2-1 … R2-7).
 */
class MeetingProfile01SourcePostBindRepublicationTest {
    @Test
    fun r2_1_preBindEmitted_peerPostBindEligible_replaysCachedBytes() {
        val harness = SourcePostBindTestSupport.newHarness("r2-1")
        SourcePostBindTestSupport.seedCreation(harness)
        val cached = SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)

        assertFalse(
            harness.sourceBridge.postBindLedger().satisfiedIdentities(harness.sessionId).isNotEmpty(),
        )

        val postBindSends = mutableListOf<ByteArray>()
        val outcome = invokePostBind(harness, postBindSends)
        assertEquals(SourcePostBindRepublishOutcome.EMITTED, outcome)
        assertEquals(1, postBindSends.size)
        assertArrayEquals(cached, postBindSends.single())
        assertTrue(harness.sourceBridge.postBindLedger().satisfiedIdentities(harness.sessionId).isNotEmpty())
    }

    @Test
    fun r2_2_postBindCallbackRepeated_atMostOneSuccessfulPublication() {
        val harness = SourcePostBindTestSupport.newHarness("r2-2")
        SourcePostBindTestSupport.seedCreation(harness)
        SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)

        var sendCount = 0
        val first =
            harness.sourceBridge.onPeerSourceConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                targetModuleId = PEER,
                reason = REASON,
                publishToPeer = { _, _ ->
                    sendCount++
                    true
                },
            )
        val second =
            harness.sourceBridge.onPeerSourceConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                targetModuleId = PEER,
                reason = REASON,
                publishToPeer = { _, _ ->
                    sendCount++
                    true
                },
            )
        assertEquals(SourcePostBindRepublishOutcome.EMITTED, first)
        assertEquals(SourcePostBindRepublishOutcome.ALREADY_SATISFIED, second)
        assertEquals(1, sendCount)
    }

    @Test
    fun r2_3_sendFailure_obligationRemainsOpen_retriesOnNextProductEvent() {
        val harness = SourcePostBindTestSupport.newHarness("r2-3")
        SourcePostBindTestSupport.seedCreation(harness)
        SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)

        val failed =
            harness.sourceBridge.onPeerSourceConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                targetModuleId = PEER,
                reason = REASON,
                publishToPeer = { _, _ -> false },
            )
        assertEquals(SourcePostBindRepublishOutcome.SEND_FAILED, failed)
        assertTrue(harness.sourceBridge.postBindLedger().satisfiedIdentities(harness.sessionId).isEmpty())

        val retrySends = mutableListOf<ByteArray>()
        val retry = invokePostBind(harness, retrySends)
        assertEquals(SourcePostBindRepublishOutcome.EMITTED, retry)
        assertEquals(1, retrySends.size)
        assertTrue(harness.sourceBridge.postBindLedger().satisfiedIdentities(harness.sessionId).isNotEmpty())
    }

    @Test
    fun r2_4_sourceGenerationChange_oldPostBindDoesNotSatisfyNewSource() {
        val harness = SourcePostBindTestSupport.newHarness("r2-4")
        SourcePostBindTestSupport.seedCreation(harness)
        SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)
        invokePostBind(harness, mutableListOf())
        val gen1Identity = harness.sourceBridge.postBindLedger().satisfiedIdentities(harness.sessionId).single()

        val replacement =
            harness.localSourceAuthority.commitLocalSource(
                sessionId = harness.sessionId,
                moduleId = HOST,
                mediaKeyEpoch = 1L,
                ssrc = 42_001,
                sourceInstanceId = ByteArray(16) { (it + 3).toByte() },
            )
        assertEquals(2L, replacement.sourceGeneration)
        harness.sourceBridge.onLocalConferenceSourceCommitted(
            sessionId = harness.sessionId,
            localModuleId = HOST,
            authoritySourceGeneration = replacement.sourceGeneration,
            ssrc = replacement.ssrc,
            sourceInstanceId = replacement.sourceInstanceId,
            mediaGroupDescriptorDigest = replacement.mediaGroupDescriptorDigest,
        )
        harness.sourceBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, _ -> true },
        )

        val gen2Sends = mutableListOf<ByteArray>()
        val outcome = invokePostBind(harness, gen2Sends)
        assertEquals(SourcePostBindRepublishOutcome.EMITTED, outcome)
        assertEquals(1, gen2Sends.size)
        val gen2Identity = harness.sourceBridge.postBindLedger().satisfiedIdentities(harness.sessionId).single {
            it.sourceGeneration == 2L
        }
        assertFalse(gen1Identity == gen2Identity)
    }

    @Test
    fun r2_4_supersededSourceWithoutTransportPublish_skipsBeforeSend() {
        val harness = SourcePostBindTestSupport.newHarness("r2-4b")
        SourcePostBindTestSupport.seedCreation(harness)
        SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)

        val firstCommitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        val replacement =
            harness.localSourceAuthority.commitLocalSource(
                sessionId = harness.sessionId,
                moduleId = HOST,
                mediaKeyEpoch = 1L,
                ssrc = firstCommitment.ssrc + 1,
                sourceInstanceId = ByteArray(16) { (it + 9).toByte() },
            )
        harness.sourceBridge.onLocalConferenceSourceCommitted(
            sessionId = harness.sessionId,
            localModuleId = HOST,
            authoritySourceGeneration = replacement.sourceGeneration,
            ssrc = replacement.ssrc,
            sourceInstanceId = replacement.sourceInstanceId,
            mediaGroupDescriptorDigest = replacement.mediaGroupDescriptorDigest,
        )

        var sendCount = 0
        val outcome =
            harness.sourceBridge.onPeerSourceConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                targetModuleId = PEER,
                reason = REASON,
                publishToPeer = { _, _ ->
                    sendCount++
                    true
                },
            )
        assertEquals(SourcePostBindRepublishOutcome.SKIPPED_NO_PRE_BIND_PUBLICATION, outcome)
        assertEquals(0, sendCount)
    }

    @Test
    fun r2_5_alreadyPostBindSatisfied_noDuplicateReplay() {
        val harness = SourcePostBindTestSupport.newHarness("r2-5")
        SourcePostBindTestSupport.seedCreation(harness)
        SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)
        invokePostBind(harness, mutableListOf())

        var sendCount = 0
        val outcome =
            harness.sourceBridge.onPeerSourceConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                targetModuleId = PEER,
                reason = REASON,
                publishToPeer = { _, _ ->
                    sendCount++
                    true
                },
            )
        assertEquals(SourcePostBindRepublishOutcome.ALREADY_SATISFIED, outcome)
        assertEquals(0, sendCount)
    }

    @Test
    fun r2_6_preBindSourceOnPeer_remainsNotRetained() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedIngress =
            MeetingProfile01FactWireIngressTestSupport.newWireIngress(
                isolatedIndex,
                MeetingProfile01PreBindFactRetention(),
            )
        val outcome =
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(
                    Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes,
                    sessionId = "unknown-session",
                ),
            )
        assertEquals(WireIngressOutcome.PRE_BIND_NOT_RETAINED, outcome)
    }

    @Test
    fun r2_7_allRepublication_byteIdenticalToCachedArtifact() {
        val harness = SourcePostBindTestSupport.newHarness("r2-7")
        SourcePostBindTestSupport.seedCreation(harness)
        val cached = SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)

        val preBindReplay = mutableListOf<ByteArray>()
        harness.sourceBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, bytes ->
                preBindReplay += bytes.copyOf()
                true
            },
        )
        assertEquals(0, preBindReplay.size)

        val postBindSends = mutableListOf<ByteArray>()
        invokePostBind(harness, postBindSends)
        assertArrayEquals(cached, postBindSends.single())
    }

    @Test
    fun r2_transportPublished_doesNotBlockFirstPostBindSend() {
        val harness = SourcePostBindTestSupport.newHarness("r2-transport")
        SourcePostBindTestSupport.seedCreation(harness)
        SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)
        val transportIdentity = harness.sourceBridge.ledger().publishedIdentities(harness.sessionId).single()
        assertTrue(harness.sourceBridge.ledger().isPublished(harness.sessionId, transportIdentity))
        assertTrue(harness.sourceBridge.postBindLedger().satisfiedIdentities(harness.sessionId).isEmpty())

        val outcome = invokePostBind(harness, mutableListOf())
        assertEquals(SourcePostBindRepublishOutcome.EMITTED, outcome)
    }

    @Test
    fun postBindRepublish_logsStructuredObs() {
        val logs = mutableListOf<String>()
        val harness = SourcePostBindTestSupport.newHarness("r2-obs", logs)
        SourcePostBindTestSupport.seedCreation(harness)
        SourcePostBindTestSupport.buildAndPublishPreBind(harness, peer = PEER)
        invokePostBind(harness, mutableListOf())

        val republish = logs.filter { it.startsWith("PROFILE01_SOURCE_POST_BIND_REPUBLISH ") }
        assertTrue(republish.any { it.contains("outcome=ATTEMPTED") })
        assertTrue(republish.any { it.contains("outcome=EMITTED") })
        assertTrue(republish.any { it.contains("reason=$REASON") })
    }

    private fun invokePostBind(
        harness: SourcePostBindTestSupport.Harness,
        sends: MutableList<ByteArray>,
    ): SourcePostBindRepublishOutcome =
        harness.sourceBridge.onPeerSourceConsumptionEligible(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            targetModuleId = PEER,
            reason = REASON,
            publishToPeer = { _, bytes ->
                sends += bytes.copyOf()
                true
            },
        )

    private fun signedFactEnvelope(
        signedBytes: ByteArray,
        sessionId: String,
    ): SignalEnvelope {
        val payload = java.util.Base64.getEncoder().encodeToString(signedBytes)
        return SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = payload,
            nonce = "n1",
            signature = "s1",
        )
    }

    companion object {
        private const val HOST = "M01"
        private const val PEER = "M02"
        private const val REASON = "admission_ready_after_answer_settled"
    }
}

internal object SourcePostBindTestSupport {
    data class Harness(
        val sessionId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val sourceBridge: MeetingProfile01SourceOriginBridge,
        val localSourceAuthority: Profile01LocalConferenceSourceIdentityAuthority,
        val ingress: com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress,
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
                members = listOf(HOST, PEER),
                actualMediaEdges = emptySet(),
            )
    }

    fun newHarness(
        sessionId: String,
        observabilityLogs: MutableList<String> = mutableListOf(),
    ): Harness {
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, "CH-$sessionId", rosterEpoch = 1L)
        val mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val registry = com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry()
        val publisherBridge =
            com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge(registry)
        val signerFixture = testSignerFixture()
        val validator =
            com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator(
                MeetingProfile01SourceOriginBridgeTestSupport.trustBoundary(
                    signerFixture.signer,
                    signerFixture.publicKeySpki,
                ),
            )
        val ingress =
            com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress(
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
                onLog = observabilityLogs::add,
            )
        return Harness(
            sessionId = sessionId,
            sessionIndex = sessionIndex,
            creationBridge = creationBridge,
            sourceBridge = sourceBridge,
            localSourceAuthority = Profile01LocalConferenceSourceIdentityAuthority(),
            ingress = ingress,
        )
    }

    fun seedCreation(harness: Harness) {
        harness.creationBridge.onTopologyPublished(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, _ -> true },
        )
        val signed = harness.creationBridge.readSignedCreationFact(harness.sessionId)!!
        harness.ingress.ingestCreationSignedFact(
            signed,
            Profile01SessionMediaSupplement(
                channelId = "CH-${harness.sessionId}",
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            ),
            Slice4MulticastNetworkConstants.DEFAULT_IFACE,
        )
    }

    fun buildAndPublishPreBind(
        harness: Harness,
        peer: String,
    ): ByteArray {
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
        harness.sourceBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            eligiblePeerModuleIds = setOf(peer),
            publishToPeer = { _, _ -> true },
        )
        return cached
    }

    private data class SignerFixture(
        val signer: Profile01PersistedSignedFactSigner,
        val publicKeySpki: ByteArray,
    )

    private fun testSignerFixture(): SignerFixture {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        val signer =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = HOST,
                signerKeyVersion = 1L,
            )!!
        return SignerFixture(signer, keyPair.public.encoded)
    }

    private const val HOST = "M01"
    private const val PEER = "M02"
}

/** Minimal trust-boundary helper for post-bind harness (mirrors source origin bridge tests). */
internal object MeetingProfile01SourceOriginBridgeTestSupport {
    fun trustBoundary(
        signer: Profile01PersistedSignedFactSigner,
        publicKeySpki: ByteArray,
    ): com.talkback.core.conference.session.profile01.Profile01WireTrustBoundary {
        return object : com.talkback.core.conference.session.profile01.Profile01WireTrustBoundary {
            override fun verifySignedFact(
                signedFactBytes: ByteArray,
            ): com.talkback.core.conference.session.profile01.Profile01WireVerificationResult {
                val envelope =
                    com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope.parse(
                        signedFactBytes,
                    )
                        ?: return com.talkback.core.conference.session.profile01.Profile01WireVerificationResult.Rejected(
                            "MALFORMED_SIGNED_FACT",
                        )
                val verify =
                    com.talkback.core.conference.session.profile01.wire.Profile01SignedFactVerifier.verifySignature(
                        envelope.fullCanonicalBytes,
                        envelope.signatureRs,
                        publicKeySpki,
                    )
                if (verify != "PASS") {
                    return com.talkback.core.conference.session.profile01.Profile01WireVerificationResult.Rejected(
                        verify,
                    )
                }
                val digest =
                    com.talkback.core.conference.session.profile01.wire.Profile01FactDigest.computeFactDigest(
                        envelope.fullCanonicalBytes,
                    )
                return com.talkback.core.conference.session.profile01.Profile01WireVerificationResult.Verified(
                    factDigest = digest.copyOf(),
                    authenticatedSignerModuleId = signer.signerModuleId,
                )
            }
        }
    }
}

/** Wire ingress factory for R2-6 (mirrors [MeetingProfile01FactWireIngressTest]). */
internal object MeetingProfile01FactWireIngressTestSupport {
    fun newWireIngress(
        sessionIndex: MeetingProfile01ConferenceSessionIndex,
        preBindRetention: MeetingProfile01PreBindFactRetention,
    ): MeetingProfile01FactWireIngress {
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val registry = com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry()
        val publisherBridge =
            com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge(registry)
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        val persistedSigner =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = "M01",
                signerKeyVersion = 1L,
            )!!
        val validator =
            com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator(
                MeetingProfile01SourceOriginBridgeTestSupport.trustBoundary(
                    persistedSigner,
                    keyPair.public.encoded,
                ),
            )
        val ingress =
            com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge = publisherBridge,
            )
        return MeetingProfile01FactWireIngress(
            ingress = ingress,
            supplementRegistry = supplementRegistry,
            sessionIndex = sessionIndex,
            preBindRetention = preBindRetention,
            incompleteApplyContinuation = MeetingProfile01IncompleteApplyContinuation(),
            networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
            localModuleId = { "M02" },
            localEstablishmentKeyVersion = { 1L },
        )
    }
}
