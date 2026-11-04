package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01SignedFactDecodeResult
import com.talkback.core.conference.session.profile01.Profile01ValidationResult
import com.talkback.core.conference.session.profile01.Profile01WireTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01WireVerificationResult
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactVerifier
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingProfile01SourceRetrySameHeadCoalescingDeskTest {
    @Test
    fun sameHeadNotInRosterRetriesCoalesceUntilNewMembershipHead() {
        val logs = mutableListOf<String>()
        val host = newHost(logs)
        host.creationBridge.onTopologyPublished(
            sessionId = host.sessionId,
            snapshot = host.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf(PEER),
            publishToPeer = { _, _ -> true },
        )
        val creationBytes = host.creationBridge.readSignedCreationFact(host.sessionId)!!
        val conferenceIdHex = host.sessionIndex.conferenceIdForSession(host.sessionId)!!

        val peer = newPeer(logs, conferenceIdHex, host.signer)
        peer.supplementRegistry.put(
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
        val commitment = peer.authority.commitLocalSource(peer.sessionId, PEER, mediaKeyEpoch = 1L)
        repeat(20) {
            val outcome =
                peer.sourceBridge.onLocalConferenceSourceCommitted(
                    sessionId = peer.sessionId,
                    localModuleId = PEER,
                    authoritySourceGeneration = commitment.sourceGeneration,
                    ssrc = commitment.ssrc,
                    sourceInstanceId = commitment.sourceInstanceId,
                    mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
                )
            assertEquals(SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, outcome)
        }

        val gapLogs =
            logs.filter {
                it.contains("PROFILE01_ORIGIN_EMIT") &&
                    it.contains("outcome=ORIGIN_SOURCE_GAP") &&
                    it.contains("moduleId=$PEER")
            }
        val coalesced = logs.filter { it.contains("PROFILE01_SOURCE_RETRY_SAME_HEAD_COALESCED") }
        assertEquals(1, gapLogs.size)
        assertEquals(1, coalesced.size)
        assertTrue(peer.sourceBridge.shouldSkipEquivalentSourceRetry(peer.sessionId, PEER))

        peer.ingress.onCreationMembershipEstablished = null
        val supplement =
            Profile01SessionMediaSupplement(
                channelId = "CH-COALESCE",
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
        val ingest = peer.ingress.ingestCreationSignedFact(
            creationBytes,
            supplement,
            Slice4MulticastNetworkConstants.DEFAULT_IFACE,
        )
        assertEquals(Profile01SignedFactDecodeResult.Ready, ingest.decode)
        assertTrue(ingest.ingress?.validation is Profile01ValidationResult.ReadySession)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, ingest.ingress?.publishOutcome)

        assertFalse(peer.sourceBridge.shouldSkipEquivalentSourceRetry(peer.sessionId, PEER))
        val built =
            peer.sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = peer.sessionId,
                localModuleId = PEER,
                authoritySourceGeneration = commitment.sourceGeneration,
                ssrc = commitment.ssrc,
                sourceInstanceId = commitment.sourceInstanceId,
                mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
                continuationTrigger = "r1b_peer_creation_applied",
            )
        assertEquals(SourceOriginBuildOutcome.BUILT, built)
        assertNotNull(peer.sourceBridge.readSignedSourceFact(peer.sessionId))
    }

    private fun newHost(logs: MutableList<String>): Env = newEnv("coalesce-host", HOST, logs)

    private fun newPeer(
        logs: MutableList<String>,
        hostConferenceIdHex: String,
        hostSigner: Signer,
    ): Env {
        val env = newEnv("coalesce-peer", PEER, logs, hostSigner)
        env.sessionIndex.bindConferenceId(env.sessionId, hostConferenceIdHex)
        return env
    }

    private fun newEnv(
        sessionId: String,
        localModuleId: String,
        logs: MutableList<String>,
        ingressTrust: Signer? = null,
    ): Env {
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, "CH-COALESCE", rosterEpoch = 1L)
        val mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val registry = ConferenceSessionMediaControlFactRegistry()
        val signer = testSigner(localModuleId)
        val trust = ingressTrust ?: signer
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = Profile01ConferenceMediaFactValidator(TestTrust(trust)),
                publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry),
                supplementRegistry = supplementRegistry,
            )
        val sourceBridge =
            MeetingProfile01SourceOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                supplementRegistry = supplementRegistry,
                membershipConvergence = ingress.membershipRegistry(),
                publisher = MeetingProfile01SourceOriginPublisher(signer.signer),
                onLog = { logs.add(it) },
            )
        val creationBridge =
            MeetingProfile01CreationOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                publisher = MeetingProfile01CreationOriginPublisher(signer.signer),
                membershipConvergence = ingress.membershipRegistry(),
            )
        return Env(
            sessionId = sessionId,
            sessionIndex = sessionIndex,
            creationBridge = creationBridge,
            sourceBridge = sourceBridge,
            ingress = ingress,
            supplementRegistry = supplementRegistry,
            authority = Profile01LocalConferenceSourceIdentityAuthority(),
            signer = signer,
        )
    }

    private data class Env(
        val sessionId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val sourceBridge: MeetingProfile01SourceOriginBridge,
        val ingress: Profile01ConferenceMediaFactIngress,
        val supplementRegistry: Profile01SessionMediaSupplementRegistry,
        val authority: Profile01LocalConferenceSourceIdentityAuthority,
        val signer: Signer,
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

    private data class Signer(
        val signer: Profile01PersistedSignedFactSigner,
        val publicKeySpki: ByteArray,
    )

    private fun testSigner(moduleId: String): Signer {
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
        return Signer(signer, keyPair.public.encoded)
    }

    private class TestTrust(private val fixture: Signer) : Profile01WireTrustBoundary {
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
