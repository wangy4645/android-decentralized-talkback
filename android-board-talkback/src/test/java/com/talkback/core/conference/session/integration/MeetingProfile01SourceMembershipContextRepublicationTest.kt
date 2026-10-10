package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01MembershipIngressResult
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01WireTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01WireVerificationResult
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSignerSource
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactVerifier
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE_DECLARATION membership-context rebuild + post-bind replay at membership head.
 */
class MeetingProfile01SourceMembershipContextRepublicationTest {
    @Test
    fun membershipHeadAdvance_sameIdentity_rebuildsSignedArtifact() {
        val harness = newHarness("src-membership-rebuild")
        harness.seedCreation()
        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        val firstBuild = harness.commitSource(commitment)
        assertEquals(SourceOriginBuildOutcome.BUILT, firstBuild)
        val epoch1Bytes = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!
        val epoch1Wire = decodeWire(epoch1Bytes)
        assertEquals(0L, epoch1Wire.membershipVersion)
        assertEquals(1L, epoch1Wire.mediaKeyEpoch)

        harness.advanceMembershipForLatePeer()

        val secondBuild = harness.commitSource(commitment)
        assertEquals(SourceOriginBuildOutcome.BUILT, secondBuild)
        val headBytes = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!
        assertTrue(!epoch1Bytes.contentEquals(headBytes))
        val headWire = decodeWire(headBytes)
        assertEquals(1L, headWire.membershipVersion)
        assertEquals(2L, headWire.mediaKeyEpoch)
        assertEquals(epoch1Wire.sourceGeneration, headWire.sourceGeneration)
    }

    @Test
    fun postBind_staleMembershipContext_rebuildThenReplaysAtHead() {
        val harness = newHarness("src-postbind-head")
        harness.seedCreation()
        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        harness.commitSource(commitment)
        val staleBytes = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!
        harness.publishPreBind(LATE_PEER)

        val stalePostBind = harness.invokePostBind(LATE_PEER, mutableListOf())
        assertEquals(SourcePostBindRepublishOutcome.EMITTED, stalePostBind)

        harness.advanceMembershipForLatePeer()
        assertEquals(SourceOriginBuildOutcome.BUILT, harness.commitSource(commitment))
        val headBytes = harness.sourceBridge.readSignedSourceFact(harness.sessionId)!!
        assertTrue(!staleBytes.contentEquals(headBytes))

        harness.publishPreBind(LATE_PEER)
        val headSends = mutableListOf<ByteArray>()
        val headPostBind = harness.invokePostBind(LATE_PEER, headSends)
        assertEquals(SourcePostBindRepublishOutcome.EMITTED, headPostBind)
        assertEquals(1, headSends.size)
        assertArrayEquals(headBytes, headSends.single())
        val headWire = decodeWire(headSends.single())
        assertEquals(1L, headWire.membershipVersion)
        assertEquals(2L, headWire.mediaKeyEpoch)
    }

    @Test
    fun postBind_beforeRebuild_skipsStaleMembershipContext() {
        val harness = newHarness("src-postbind-skip-stale")
        harness.seedCreation()
        val commitment = harness.localSourceAuthority.commitLocalSource(harness.sessionId, HOST, mediaKeyEpoch = 1L)
        harness.commitSource(commitment)
        harness.publishPreBind(LATE_PEER)
        harness.advanceMembershipForLatePeer()

        var sendCount = 0
        val outcome =
            harness.sourceBridge.onPeerSourceConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.trioSnapshot(),
                localModuleId = HOST,
                targetModuleId = LATE_PEER,
                reason = REASON,
                publishToPeer = { _, _ ->
                    sendCount++
                    true
                },
            )
        assertEquals(SourcePostBindRepublishOutcome.SKIPPED_STALE_MEMBERSHIP_CONTEXT, outcome)
        assertEquals(0, sendCount)
    }

    private fun decodeWire(
        signedBytes: ByteArray,
    ): com.talkback.core.conference.session.profile01.Profile01WireMemberSourceFact =
        (
            Profile01WireCborDecoder.decodeSourceDeclarationMember(signedBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready
        ).value

    private data class Harness(
        val sessionId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val membershipBridge: MeetingProfile01MembershipOriginBridge,
        val sourceBridge: MeetingProfile01SourceOriginBridge,
        val localSourceAuthority: Profile01LocalConferenceSourceIdentityAuthority,
        val ingress: Profile01ConferenceMediaFactIngress,
        val supplementRegistry: Profile01SessionMediaSupplementRegistry,
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

        fun trioSnapshot(): ConferenceTopologySnapshot =
            duoSnapshot().copy(
                rosterEpoch = 2L,
                members = listOf(HOST, PEER, LATE_PEER),
            )

        fun seedCreation() {
            creationBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf(PEER),
                publishToPeer = { _, _ -> true },
            )
            val signed = creationBridge.readSignedCreationFact(sessionId)!!
            ingress.ingestCreationSignedFact(
                signed,
                Profile01SessionMediaSupplement(
                    channelId = "CH-$sessionId",
                    masterKey = ByteArray(16),
                    masterSalt = ByteArray(12),
                    keyContextHint64 = ByteArray(8),
                ),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        }

        fun advanceMembershipForLatePeer() {
            val emitted = AtomicReference<ByteArray>()
            membershipBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = trioSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf(PEER, LATE_PEER),
                publishToPeer = { moduleId, bytes ->
                    if (moduleId == LATE_PEER) {
                        emitted.set(bytes)
                    }
                    true
                },
            )
            val signed = emitted.get()
            requireNotNull(signed)
            val decoded =
                (Profile01WireCborDecoder.decodeMembership(signed) as Profile01WireCborDecoder.DecodeResult.Ready).value
            supplementRegistry.put(
                Profile01DerivedMediaKeyMaterial(
                    conferenceId = sessionIndex.ensureConferenceIdHex(sessionId),
                    conferenceEpoch = decoded.conferenceEpoch,
                    membershipVersion = decoded.membershipVersion,
                    mediaKeyEpoch = decoded.mediaKeyEpoch,
                    masterKey = ByteArray(16),
                    masterSalt = ByteArray(12),
                    keyContextHint64 = ByteArray(8),
                    membershipKeyContextDigest = ByteArray(32),
                ),
            )
            val result = ingress.ingestMembershipSignedFact(signed)
            assertTrue(result is Profile01MembershipIngressResult.Converged)
        }

        fun commitSource(
            commitment: Profile01LocalConferenceSourceIdentityAuthority.Commitment,
        ): SourceOriginBuildOutcome =
            sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = sessionId,
                localModuleId = HOST,
                authoritySourceGeneration = commitment.sourceGeneration,
                ssrc = commitment.ssrc,
                sourceInstanceId = commitment.sourceInstanceId,
                mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
            )

        fun publishPreBind(peer: String) {
            sourceBridge.onEligiblePeers(
                sessionId = sessionId,
                snapshot = trioSnapshot(),
                localModuleId = HOST,
                eligiblePeerModuleIds = setOf(peer),
                publishToPeer = { _, _ -> true },
            )
        }

        fun invokePostBind(
            peer: String,
            sends: MutableList<ByteArray>,
        ): SourcePostBindRepublishOutcome =
            sourceBridge.onPeerSourceConsumptionEligible(
                sessionId = sessionId,
                snapshot = trioSnapshot(),
                localModuleId = HOST,
                targetModuleId = peer,
                reason = REASON,
                publishToPeer = { _, bytes ->
                    sends += bytes.copyOf()
                    true
                },
            )
    }

    private fun newHarness(sessionId: String): Harness {
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, "CH-$sessionId", rosterEpoch = 1L)
        val mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val registry = ConferenceSessionMediaControlFactRegistry()
        val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val signerFixture = testSignerFixture()
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
                signerSource = Profile01SignedFactSignerSource.fixed(signerFixture.signer),
                membershipConvergence = ingress.membershipRegistry(),
            )
        val membershipBridge =
            MeetingProfile01MembershipOriginBridge(
                sessionIndex = sessionIndex,
                creationOriginBridge = creationBridge,
                mediaKeyAuthority = mediaKeyAuthority,
                membershipConvergence = ingress.membershipRegistry(),
                signerSource = Profile01SignedFactSignerSource.fixed(signerFixture.signer),
            )
        val sourceBridge =
            MeetingProfile01SourceOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                supplementRegistry = supplementRegistry,
                membershipConvergence = ingress.membershipRegistry(),
                signerSource = Profile01SignedFactSignerSource.fixed(signerFixture.signer),
            )
        return Harness(
            sessionId = sessionId,
            sessionIndex = sessionIndex,
            creationBridge = creationBridge,
            membershipBridge = membershipBridge,
            sourceBridge = sourceBridge,
            localSourceAuthority = Profile01LocalConferenceSourceIdentityAuthority(),
            ingress = ingress,
            supplementRegistry = supplementRegistry,
        )
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

    private class TestModuleTrustBoundary(
        private val fixture: SignerFixture,
    ) : Profile01WireTrustBoundary {
        override fun verifySignedFact(
            signedFactBytes: ByteArray,
        ): Profile01WireVerificationResult {
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
        private const val LATE_PEER = "M03"
        private const val REASON = "admission_ready_after_answer_settled"
    }
}
