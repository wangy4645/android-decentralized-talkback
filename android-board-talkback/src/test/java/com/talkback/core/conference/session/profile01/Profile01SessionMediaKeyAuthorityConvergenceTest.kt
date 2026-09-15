package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.ConferenceMediaMemberControlFact
import com.talkback.core.conference.session.ConferenceMediaSessionControlFact
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.gbc.AuthoritativeConferenceMediaSessionDeclaration
import com.talkback.core.conference.session.gbc.ConferenceMediaDeclarationDisposition
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.integration.HostLocalSupplementMaterializationRequest
import com.talkback.core.conference.session.integration.MeetingProfile01AwareConferenceSessionMediaFactPort
import com.talkback.core.conference.session.integration.MeetingProfile01ConferenceSessionIndex
import com.talkback.core.conference.session.integration.Profile01HostLocalMediaSupplementMaterializer
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.integration.HostSessionProjectionOutcome
import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.session.integration.Profile01HostLocalSessionFactProjection
import com.talkback.core.conference.session.profile01.Profile01MembershipIngressResult
import com.talkback.core.conference.session.profile01.Profile01SignedFactDecodeResult
import com.talkback.core.conference.session.profile01.wire.Profile01Q5EmbeddedVectors
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaGroupDescriptor
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuildRequest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuildResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipIncarnationAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01PackageRecipientBinding
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes
import com.talkback.core.conference.transport.SourceScopedSrtpEgress
import com.talkback.core.conference.session.integration.Profile01ShadowSessionReplay
import com.talkback.core.conference.session.profile01.wire.Profile01PackageCrypto
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.wire.ConferenceWireEgress
import com.talkback.core.conference.wire.WireIngressResult
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R2-P2 STALE-SESSION-KEY-REPUBLICATION — cross-node crypto + field-ordering regression.
 *
 * Single authority ([Profile01ConferenceMediaKeyMaterialAuthority]); defect family is stale
 * session republication copying epoch-N-1 keys into epoch-N registry — not a second random producer.
 *
 * Safety: when epoch-2 authoritative material is unavailable, defer — never publish epoch-2 + stale epoch-1 crypto.
 */
class Profile01SessionMediaKeyAuthorityConvergenceTest {
    private val sessionId = "auth-conv-session"
    private val channelId = Profile01DirectedWireFixtures.CHANNEL_ID
    private val conferenceIdHex = Profile01DirectedWireFixtures.CONFERENCE_ID
    private val hostModuleId = "M01"
    private val peerModuleId = "M02"
    private val conferenceEpoch = 7L
    private val hostSourceIdentity = hostModuleId
    private val peerSourceIdentity = peerModuleId
    private val hostSsrc = 0x60616263
    private val peerSsrc = 0x70717273
    private val hostIncarnation = 3L
    private val peerIncarnation = 2L
    private val admissionKey48 = Profile01DirectedWireFixtures.hex("fe3e3a6cb214")

    private val sessionIndex = MeetingProfile01ConferenceSessionIndex()
    private val mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
    private val supplementRegistry = Profile01SessionMediaSupplementRegistry()
    private val controlRegistry = ConferenceSessionMediaControlFactRegistry()
    private val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(controlRegistry)
    private val validator =
        Profile01ConferenceMediaFactValidator(Profile01DirectedWireFixtures.goldenVectorTrustBoundary())
    private val ingress =
        Profile01ConferenceMediaFactIngress(
            validator = validator,
            publisherBridge = publisherBridge,
            mediaKeyDecrypt =
                Profile01MediaKeyPackageDecryptSeam(
                    Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam(),
                ),
            supplementRegistry = supplementRegistry,
        )
    private val factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(controlRegistry, sessionIndex)
    private lateinit var materializer: Profile01HostLocalMediaSupplementMaterializer
    private lateinit var hostWiring: ConferenceSessionMediaWiring
    private lateinit var peerWiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, conferenceIdHex)
        materializer =
            Profile01HostLocalMediaSupplementMaterializer(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                supplementRegistry = supplementRegistry,
            )
        hostWiring = ConferenceSessionMediaWiring.forHarness()
        peerWiring = ConferenceSessionMediaWiring.forHarness()
    }

    @After
    fun tearDown() {
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun republication_doesNotCopyStaleKeysAcrossMediaKeyEpochAdvance() {
        val epoch1Keys = keyBytes(label = 1)
        bootstrapSessionDeclaration(
            mediaKeyEpoch = 1L,
            membershipVersion = 0L,
            masterKey = epoch1Keys.masterKey,
            masterSalt = epoch1Keys.masterSalt,
            keyContextHint64 = epoch1Keys.keyContextHint64,
        )
        seedCreationMembershipHead()

        val result = ingress.ingestMembershipSignedFact(Profile01DirectedWireFixtures.membershipSignedFactBytes)
        assertTrue(result is Profile01MembershipIngressResult.Converged)

        val published = controlRegistry.session(conferenceIdHex)
        assertNotNull(published)
        assertEquals(1L, published!!.mediaKeyEpoch)
        assertArrayEquals(epoch1Keys.masterKey, published.masterKey)
    }

    @Test
    fun hostSupplementAndPackageRecover_sameContextFingerprintAtEpochOne() {
        val epoch = 1L
        val authority = ensureAuthorityMaterial(epoch, membershipVersion = 0L)
        materializeHostSupplement(epoch, membershipVersion = 0L, authority)
        assertEquals(
            HostSessionProjectionOutcome.APPLIED,
            hostProjection().maybeProjectHostShadowSession(sessionId),
        )

        val packageMaterial = buildPackageFromAuthority(authority, epoch, membershipVersion = 0L)
        val peerContext = decryptPackage(packageMaterial)

        assertContextFingerprint(
            hostContextFromRegistry(epoch),
            peerContext,
        )
    }

    @Test
    fun crossNode_hostEncrypt_peerDecrypt_aeadPass() {
        val epoch = 1L
        val authority = ensureAuthorityMaterial(epoch, membershipVersion = 0L)
        materializeHostSupplement(epoch, membershipVersion = 0L, authority)
        installHostSessionFromSupplement(epoch, membershipVersion = 0L)
        installMemberReady(hostWiring, hostSourceIdentity, hostSsrc, hostIncarnation, epoch, membershipVersion = 0L)

        val packageMaterial = buildPackageFromAuthority(authority, epoch, membershipVersion = 0L)
        val peerContext = decryptPackage(packageMaterial)
        installPeerSessionFromRecoveredContext(epoch, membershipVersion = 0L, peerContext)
        installMemberReady(peerWiring, hostSourceIdentity, hostSsrc, hostIncarnation, epoch, membershipVersion = 0L)

        val protected = hostEncrypt(hostSourceIdentity, hostSsrc, hostContextFromRegistry(epoch))
        val result = peerWiring.authorityRuntime(sessionId)!!.admitWire(hostSourceIdentity, protected.udpPayload)
        assertTrue(result is WireIngressResult.Accepted)
    }

    @Test
    fun crossNode_peerEncrypt_hostDecrypt_aeadPass() {
        val epoch = 1L
        val authority = ensureAuthorityMaterial(epoch, membershipVersion = 0L)
        materializeHostSupplement(epoch, membershipVersion = 0L, authority)
        installHostSessionFromSupplement(epoch, membershipVersion = 0L)

        val packageMaterial = buildPackageFromAuthority(authority, epoch, membershipVersion = 0L)
        val peerContext = decryptPackage(packageMaterial)
        installPeerSessionFromRecoveredContext(epoch, membershipVersion = 0L, peerContext)
        installMemberReady(peerWiring, peerSourceIdentity, peerSsrc, peerIncarnation, epoch, membershipVersion = 0L)

        installMemberReady(hostWiring, peerSourceIdentity, peerSsrc, peerIncarnation, epoch, membershipVersion = 0L)

        val protected = peerEncrypt(peerSourceIdentity, peerSsrc, peerContext)
        val result = hostWiring.authorityRuntime(sessionId)!!.admitWire(peerSourceIdentity, protected.udpPayload)
        assertTrue(result is WireIngressResult.Accepted)
    }

    @Test
    fun epochSuccession_hostPeerContextsMatch_andStaleEpochOneCannotOverwriteEpochTwo() {
        val authorityEpoch1 = ensureAuthorityMaterial(mediaKeyEpoch = 1L, membershipVersion = 0L)
        materializeHostSupplement(1L, membershipVersion = 0L, authorityEpoch1)
        bootstrapSessionDeclaration(
            mediaKeyEpoch = 1L,
            membershipVersion = 0L,
            masterKey = supplementRegistry.lookup(conferenceIdHex, 1L)!!.masterKey,
            masterSalt = supplementRegistry.lookup(conferenceIdHex, 1L)!!.masterSalt,
            keyContextHint64 = supplementRegistry.lookup(conferenceIdHex, 1L)!!.keyContextHint64,
        )

        val authorityEpoch2 =
            rotateAuthorityMaterial(
                previousDigest = authorityEpoch1.membershipKeyContextDigest,
                mediaKeyEpoch = 2L,
                membershipVersion = 1L,
            )
        materializeHostSupplement(2L, membershipVersion = 1L, authorityEpoch2)
        seedCreationMembershipHead()
        validator.membershipRegistry().applyMembership(Profile01DirectedWireFixtures.decodeMembershipWireFact())

        val sync =
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = harnessNetworkInterfaceName(),
                creationSignedBytesForEndpoint = Profile01DirectedWireFixtures.creationSignedFactBytes,
            )
        assertEquals(SessionHeadSyncOutcome.APPLIED, sync)

        val hostEpoch2 = hostContextFromRegistry(2L)
        val packageEpoch2 = decryptPackage(buildPackageFromAuthority(authorityEpoch2, 2L, membershipVersion = 1L))
        assertContextFingerprint(hostEpoch2, packageEpoch2)

        installHostSessionFromSupplement(2L, membershipVersion = 1L)
        installPeerSessionFromRecoveredContext(2L, membershipVersion = 1L, packageEpoch2)
        installMemberReady(hostWiring, hostSourceIdentity, hostSsrc, hostIncarnation, 2L, membershipVersion = 1L)
        installMemberReady(peerWiring, hostSourceIdentity, hostSsrc, hostIncarnation, 2L, membershipVersion = 1L)

        val protected = hostEncrypt(hostSourceIdentity, hostSsrc, hostEpoch2)
        assertTrue(
            peerWiring.authorityRuntime(sessionId)!!.admitWire(hostSourceIdentity, protected.udpPayload)
                is WireIngressResult.Accepted,
        )

        val staleSync =
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = harnessNetworkInterfaceName(),
                creationSignedBytesForEndpoint = Profile01DirectedWireFixtures.creationSignedFactBytes,
            )
        assertEquals(SessionHeadSyncOutcome.ALREADY_SYNCED, staleSync)
        assertContextFingerprint(hostContextFromRegistry(2L), packageEpoch2)
        assertFalse(
            hostContextFromRegistry(2L).masterKey.contentEquals(
                supplementRegistry.lookup(conferenceIdHex, 1L)!!.masterKey,
            ),
        )
    }

    /**
     * Field first divergence: membership ingress advances mediaKeyEpoch before supplement/sync.
     *
     * Old bug: republicationFor() published epoch-2 registry row still carrying epoch-1 keys (keyA).
     * Fix: republicationFor() returns null on epoch advance; sync + wiring refresh converge to authority keyB.
     *
     * Safety: without epoch-2 supplement, sync must not fabricate epoch-2 + keyA — defer instead.
     */
    @Test
    fun membershipEpochAdvance_mustNotRepublishPriorEpochMediaKeys() {
        val authorityEpoch1 = ensureAuthorityMaterial(mediaKeyEpoch = 1L, membershipVersion = 0L)
        materializeHostSupplement(1L, membershipVersion = 0L, authorityEpoch1)
        val contextA = deriveContextFromAuthority(authorityEpoch1, mediaKeyEpoch = 1L)
        bootstrapSessionDeclaration(
            mediaKeyEpoch = 1L,
            membershipVersion = 0L,
            masterKey = contextA.masterKey,
            masterSalt = contextA.masterSalt,
            keyContextHint64 = contextA.keyContextHint64,
        )
        seedCreationMembershipHead()
        installHostSessionFromSupplement(1L, membershipVersion = 0L)
        assertArrayEquals(contextA.masterKey, hostWiringKeyContext(1L).masterKey)

        val membershipWire = Profile01DirectedWireFixtures.decodeMembershipWireFact()
        assertEquals(1L, membershipWire.membershipVersion)
        assertEquals(2L, membershipWire.mediaKeyEpoch)

        // Field ordering: membership ingress only — deliberately NO sync yet.
        val membershipIngress =
            ingress.ingestMembershipSignedFact(Profile01DirectedWireFixtures.membershipSignedFactBytes)
        assertTrue(membershipIngress is Profile01MembershipIngressResult.Converged)

        val membershipHead = validator.membershipRegistry().current(conferenceIdHex)!!
        assertEquals(2L, membershipHead.mediaKeyEpoch)
        assertEquals(1L, membershipHead.membershipVersion)

        val afterMembershipIngress = controlRegistry.session(conferenceIdHex)!!
        assertRegistryMustNotBeStaleEpochRepublish(
            published = afterMembershipIngress,
            targetEpoch = 2L,
            staleContext = contextA,
        )
        assertEquals(1L, afterMembershipIngress.mediaKeyEpoch)
        assertArrayEquals(contextA.masterKey, afterMembershipIngress.masterKey)

        val authorityEpoch2 =
            rotateAuthorityMaterial(
                previousDigest = authorityEpoch1.membershipKeyContextDigest,
                mediaKeyEpoch = 2L,
                membershipVersion = 1L,
            )
        val contextB = deriveContextFromAuthority(authorityEpoch2, mediaKeyEpoch = 2L)
        assertFalse(contextA.masterKey.contentEquals(contextB.masterKey))

        // Negative: epoch-2 authority exists but supplement not ready — must not copy keyA forward.
        assertEquals(
            SessionHeadSyncOutcome.NO_SUPPLEMENT,
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = harnessNetworkInterfaceName(),
                creationSignedBytesForEndpoint = Profile01DirectedWireFixtures.creationSignedFactBytes,
            ),
        )
        assertRegistryMustNotBeStaleEpochRepublish(
            published = controlRegistry.session(conferenceIdHex)!!,
            targetEpoch = 2L,
            staleContext = contextA,
        )

        materializeHostSupplement(2L, membershipVersion = 1L, authorityEpoch2)
        assertEquals(
            SessionHeadSyncOutcome.APPLIED,
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = harnessNetworkInterfaceName(),
                creationSignedBytesForEndpoint = Profile01DirectedWireFixtures.creationSignedFactBytes,
            ),
        )
        assertContextFingerprint(contextB, hostContextFromRegistry(2L))

        sessionIndex.updateRosterEpoch(sessionId, 1L)
        Profile01ShadowSessionReplay.replaySessionStarted(
            sessionId = sessionId,
            channelId = channelId,
            membershipVersionForRead = 1L,
        )
        assertEquals(2L, hostWiring.currentMediaKeyEpoch(sessionId))
        assertArrayEquals(contextB.masterKey, hostWiringKeyContext(2L).masterKey)

        // Same-epoch repair: registry contaminated with keyA, sync + replay refresh wiring back to keyB.
        bootstrapSessionDeclaration(
            mediaKeyEpoch = 2L,
            membershipVersion = 1L,
            masterKey = contextA.masterKey,
            masterSalt = contextA.masterSalt,
            keyContextHint64 = contextA.keyContextHint64,
        )
        assertArrayEquals(contextA.masterKey, controlRegistry.session(conferenceIdHex)!!.masterKey)
        sessionIndex.updateRosterEpoch(sessionId, 1L)
        Profile01ShadowSessionReplay.replaySessionStarted(
            sessionId = sessionId,
            channelId = channelId,
            membershipVersionForRead = 1L,
        )
        assertArrayEquals(contextA.masterKey, hostWiringKeyContext(2L).masterKey)

        assertEquals(
            SessionHeadSyncOutcome.APPLIED,
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = harnessNetworkInterfaceName(),
                creationSignedBytesForEndpoint = Profile01DirectedWireFixtures.creationSignedFactBytes,
            ),
        )
        assertArrayEquals(contextB.masterKey, controlRegistry.session(conferenceIdHex)!!.masterKey)
        sessionIndex.updateRosterEpoch(sessionId, 1L)
        Profile01ShadowSessionReplay.replaySessionStarted(
            sessionId = sessionId,
            channelId = channelId,
            membershipVersionForRead = 1L,
        )
        assertArrayEquals(contextB.masterKey, hostWiringKeyContext(2L).masterKey)

        val packageEpoch2 = decryptPackage(buildPackageFromAuthority(authorityEpoch2, 2L, membershipVersion = 1L))
        assertContextFingerprint(contextB, packageEpoch2)
        installPeerSessionFromRecoveredContext(2L, membershipVersion = 1L, packageEpoch2)
        installMemberReady(hostWiring, hostSourceIdentity, hostSsrc, hostIncarnation, 2L, membershipVersion = 1L)
        installMemberReady(peerWiring, hostSourceIdentity, hostSsrc, hostIncarnation, 2L, membershipVersion = 1L)
        installMemberReady(peerWiring, peerSourceIdentity, peerSsrc, peerIncarnation, 2L, membershipVersion = 1L)
        installMemberReady(hostWiring, peerSourceIdentity, peerSsrc, peerIncarnation, 2L, membershipVersion = 1L)

        val hostToPeer =
            hostEncrypt(hostSourceIdentity, hostSsrc, hostContextFromRegistry(2L))
        assertTrue(
            peerWiring.authorityRuntime(sessionId)!!.admitWire(hostSourceIdentity, hostToPeer.udpPayload)
                is WireIngressResult.Accepted,
        )
        val peerToHost = peerEncrypt(peerSourceIdentity, peerSsrc, packageEpoch2)
        assertTrue(
            hostWiring.authorityRuntime(sessionId)!!.admitWire(peerSourceIdentity, peerToHost.udpPayload)
                is WireIngressResult.Accepted,
        )
    }

    private fun deriveContextFromAuthority(
        authority: Profile01ConferenceMediaKeyMaterialAuthority.Material,
        mediaKeyEpoch: Long,
    ): MediaKeyContextBytes {
        val conferenceId = conferenceIdHex.hexToId128Bytes()
        val srtp =
            Profile01PackageCrypto.deriveSrtpMaterial(
                authority.conferenceMediaSecret,
                authority.membershipKeyContextDigest,
            )
        val hint =
            Profile01PackageCrypto.deriveKeyContextHint64(
                conferenceId,
                conferenceEpoch,
                mediaKeyEpoch,
                authority.membershipKeyContextDigest,
            )
        return MediaKeyContextBytes(
            masterKey = srtp.masterKey.copyOf(),
            masterSalt = srtp.masterSalt.copyOf(),
            keyContextHint64 = hint.copyOf(),
        )
    }

    /**
     * Stale republication must never publish targetEpoch with prior-epoch crypto material.
     * Prefer deferred/not-ready over epoch advance + stale keys.
     */
    private fun assertRegistryMustNotBeStaleEpochRepublish(
        published: ConferenceMediaSessionControlFact,
        targetEpoch: Long,
        staleContext: MediaKeyContextBytes,
    ) {
        assertFalse(
            "registry must not be epoch-$targetEpoch with stale prior-epoch keys",
            published.mediaKeyEpoch == targetEpoch &&
                published.masterKey.contentEquals(staleContext.masterKey) &&
                published.masterSalt.contentEquals(staleContext.masterSalt) &&
                published.keyContextHint64.contentEquals(staleContext.keyContextHint64),
        )
    }

    private fun hostWiringKeyContext(mediaKeyEpoch: Long): MediaKeyContextBytes {
        val key =
            hostWiring.authorityRuntime(sessionId)!!.store.verifiedFactSeam().allKeys()[mediaKeyEpoch]
                ?: error("missing wiring key for mediaKeyEpoch=$mediaKeyEpoch")
        return MediaKeyContextBytes(
            masterKey = key.masterKey.copyOf(),
            masterSalt = key.masterSalt.copyOf(),
            keyContextHint64 = key.keyContextHint64.copyOf(),
        )
    }

    private data class MediaKeyContextBytes(
        val masterKey: ByteArray,
        val masterSalt: ByteArray,
        val keyContextHint64: ByteArray,
    )

    private fun keyBytes(label: Int): MediaKeyContextBytes =
        MediaKeyContextBytes(
            masterKey = ByteArray(16) { (it + label).toByte() },
            masterSalt = ByteArray(12) { (it + label * 2).toByte() },
            keyContextHint64 = ByteArray(8) { (it + label * 3).toByte() },
        )

    private fun ensureAuthorityMaterial(
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ): Profile01ConferenceMediaKeyMaterialAuthority.Material {
        val conferenceId = conferenceIdHex.hexToId128Bytes()
        val membershipView = membershipView(membershipVersion)
        val descriptorDigest = Profile01FactDigest.descriptorDigest(Profile01MediaGroupDescriptor.productionDescriptor())
        return mediaKeyAuthority.ensureMaterial(
            sessionId = sessionId,
            conferenceId = conferenceId,
            conferenceEpoch = conferenceEpoch,
            ownerModuleId = hostModuleId,
            mediaGroupDescriptorDigest = descriptorDigest,
            membershipView = membershipView,
            membershipVersion = membershipVersion,
            mediaKeyEpoch = mediaKeyEpoch,
        )
    }

    private fun rotateAuthorityMaterial(
        previousDigest: ByteArray,
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ): Profile01ConferenceMediaKeyMaterialAuthority.Material {
        val conferenceId = conferenceIdHex.hexToId128Bytes()
        val descriptorDigest = Profile01FactDigest.descriptorDigest(Profile01MediaGroupDescriptor.productionDescriptor())
        return mediaKeyAuthority.rotateForMembership(
            sessionId = sessionId,
            conferenceId = conferenceId,
            conferenceEpoch = conferenceEpoch,
            ownerModuleId = hostModuleId,
            mediaGroupDescriptorDigest = descriptorDigest,
            membershipVersion = membershipVersion,
            previousMembershipDigest = previousDigest,
            membershipView = membershipView(membershipVersion),
            mediaKeyEpoch = mediaKeyEpoch,
        )
    }

    private fun membershipView(membershipVersion: Long): List<Profile01WireMembershipMember> {
        val conferenceId = conferenceIdHex.hexToId128Bytes()
        val modules =
            if (membershipVersion == 0L) {
                listOf(hostModuleId)
            } else {
                listOf(hostModuleId, peerModuleId)
            }
        return modules.map { moduleId ->
            Profile01WireMembershipMember(
                moduleId = moduleId,
                membershipIncarnationId =
                    Profile01MembershipIncarnationAuthority.deriveIncarnationId128(
                        conferenceId = conferenceId,
                        moduleId = moduleId,
                    ),
            )
        }
    }

    private fun materializeHostSupplement(
        mediaKeyEpoch: Long,
        membershipVersion: Long,
        authority: Profile01ConferenceMediaKeyMaterialAuthority.Material,
    ) {
        materializer.materializeFromAuthority(
            HostLocalSupplementMaterializationRequest(
                sessionId = sessionId,
                conferenceIdHex = conferenceIdHex,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                expectedMediaKeyCommitment = authority.mediaKeyCommitment.copyOf(),
            ),
        )
    }

    private fun bootstrapSessionDeclaration(
        mediaKeyEpoch: Long,
        membershipVersion: Long,
        masterKey: ByteArray,
        masterSalt: ByteArray,
        keyContextHint64: ByteArray,
    ) {
        val declaration =
            AuthoritativeConferenceMediaSessionDeclaration(
                conferenceId = conferenceIdHex,
                channelId = channelId,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                endpoint =
                    Profile01DirectedWireFixtures.decodeSessionWireFact(
                        Profile01SessionMediaSupplement(
                            channelId = channelId,
                            masterKey = masterKey.copyOf(),
                            masterSalt = masterSalt.copyOf(),
                            keyContextHint64 = keyContextHint64.copyOf(),
                        ),
                    ).endpoint,
                networkInterfaceName = harnessNetworkInterfaceName(),
                masterKey = masterKey.copyOf(),
                masterSalt = masterSalt.copyOf(),
                keyContextHint64 = keyContextHint64.copyOf(),
                factGeneration = conferenceEpoch * 1_000L + membershipVersion,
                disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
            )
        validator.bootstrapSessionDeclaration(declaration)
        publisherBridge.publishSessionDeclaration(declaration)
    }

    private fun seedCreationMembershipHead() {
        val supplement = supplementRegistry.lookup(conferenceIdHex, 1L)
        val placeholder =
            supplement?.let {
                Profile01SessionMediaSupplement(
                    channelId = channelId,
                    masterKey = it.masterKey.copyOf(),
                    masterSalt = it.masterSalt.copyOf(),
                    keyContextHint64 = it.keyContextHint64.copyOf(),
                )
            }
                ?: Profile01SessionMediaSupplement(
                    channelId = channelId,
                    masterKey = ByteArray(16),
                    masterSalt = ByteArray(12),
                    keyContextHint64 = ByteArray(8),
                )
        val creationWire = Profile01DirectedWireFixtures.decodeSessionWireFact(placeholder)
        validator.membershipRegistry().seedFromCreation(creationWire)
    }

    private fun hostContextFromRegistry(mediaKeyEpoch: Long): MediaKeyContextBytes {
        val session = controlRegistry.session(conferenceIdHex)!!
        assertEquals(mediaKeyEpoch, session.mediaKeyEpoch)
        return MediaKeyContextBytes(
            masterKey = session.masterKey.copyOf(),
            masterSalt = session.masterSalt.copyOf(),
            keyContextHint64 = session.keyContextHint64.copyOf(),
        )
    }

    private fun assertContextFingerprint(
        left: MediaKeyContextBytes,
        right: MediaKeyContextBytes,
    ) {
        assertArrayEquals(left.masterKey, right.masterKey)
        assertArrayEquals(left.masterSalt, right.masterSalt)
        assertArrayEquals(left.keyContextHint64, right.keyContextHint64)
    }

    private fun installHostSessionFromSupplement(
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ) {
        ConferenceSessionMediaBridge.wiring = hostWiring
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        sessionIndex.updateRosterEpoch(sessionId, membershipVersion)
        if (!ConferenceSessionMediaBridge.hasSession(sessionId)) {
            if (controlRegistry.session(conferenceIdHex) != null) {
                Profile01ShadowSessionReplay.replaySessionStarted(
                    sessionId = sessionId,
                    channelId = channelId,
                    membershipVersionForRead = membershipVersion,
                )
            } else {
                assertEquals(
                    HostSessionProjectionOutcome.APPLIED,
                    hostProjection().maybeProjectHostShadowSession(sessionId),
                )
            }
        } else if (hostWiring.currentMediaKeyEpoch(sessionId) != mediaKeyEpoch) {
            Profile01ShadowSessionReplay.replaySessionStarted(
                sessionId = sessionId,
                channelId = channelId,
                membershipVersionForRead = membershipVersion,
            )
        }
        assertTrue(ConferenceSessionMediaBridge.hasSession(sessionId))
        assertEquals(mediaKeyEpoch, hostWiring.currentMediaKeyEpoch(sessionId))
    }

    private fun hostProjection(): Profile01HostLocalSessionFactProjection =
        Profile01HostLocalSessionFactProjection(
            readSignedCreationFact = { Profile01DirectedWireFixtures.creationSignedFactBytes },
            readSignedSourceFact = { null },
            ingress = ingress,
            supplementRegistry = supplementRegistry,
            sessionIndex = sessionIndex,
            registry = controlRegistry,
            networkInterfaceName = { harnessNetworkInterfaceName() },
        )

    private fun installPeerSessionFromRecoveredContext(
        mediaKeyEpoch: Long,
        membershipVersion: Long,
        context: MediaKeyContextBytes,
    ) {
        val supplement =
            Profile01SessionMediaSupplement(
                channelId = channelId,
                masterKey = context.masterKey.copyOf(),
                masterSalt = context.masterSalt.copyOf(),
                keyContextHint64 = context.keyContextHint64.copyOf(),
            )
        supplementRegistry.put(
            Profile01DerivedMediaKeyMaterial(
                conferenceId = conferenceIdHex,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                masterKey = context.masterKey.copyOf(),
                masterSalt = context.masterSalt.copyOf(),
                keyContextHint64 = context.keyContextHint64.copyOf(),
                membershipKeyContextDigest = ByteArray(32),
            ),
        )
        val publishedSession = controlRegistry.session(conferenceIdHex)
        if (publishedSession == null || publishedSession.mediaKeyEpoch != mediaKeyEpoch) {
            val creationResult =
                ingress.ingestCreationSignedFact(
                    signedFactBytes = Profile01DirectedWireFixtures.creationSignedFactBytes,
                    supplement = supplement,
                    networkInterfaceName = harnessNetworkInterfaceName(),
                )
            assertTrue(creationResult.decode is Profile01SignedFactDecodeResult.Ready)
            assertTrue(
                creationResult.ingress?.publishOutcome == ControlFactPublishOutcome.ACCEPTED ||
                    creationResult.ingress?.publishOutcome == ControlFactPublishOutcome.IDEMPOTENT,
            )
            val membership =
                ingress.ingestMembershipSignedFact(Profile01DirectedWireFixtures.membershipSignedFactBytes)
            assertTrue(membership is Profile01MembershipIngressResult.Converged)
        }
        ConferenceSessionMediaBridge.wiring = peerWiring
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        sessionIndex.updateRosterEpoch(sessionId, membershipVersion)
        Profile01ShadowSessionReplay.replaySessionStarted(
            sessionId = sessionId,
            channelId = channelId,
            membershipVersionForRead = membershipVersion,
        )
        assertTrue(ConferenceSessionMediaBridge.hasSession(sessionId))
    }

    private fun installMemberReady(
        wiring: ConferenceSessionMediaWiring,
        sourceIdentity: String,
        ssrc: Int,
        incarnationId: Long,
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ) {
        publishMemberBinding(sourceIdentity, ssrc, incarnationId, mediaKeyEpoch, membershipVersion)
        ConferenceSessionMediaBridge.wiring = wiring
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, sourceIdentity)
    }

    private fun publishMemberBinding(
        moduleId: String,
        ssrc: Int,
        incarnationId: Long,
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ) {
        controlRegistry.publishMember(
            ConferenceMediaMemberControlFact(
                conferenceId = conferenceIdHex,
                moduleId = moduleId,
                membershipIncarnationId = incarnationId,
                ssrc = ssrc,
                sourceAdmissionKey48 = admissionKey48.copyOf(),
                mediaKeyEpoch = mediaKeyEpoch,
                membershipVersion = membershipVersion,
                factGeneration = conferenceEpoch * 1_000L + membershipVersion,
            ),
        )
    }

    private fun buildPackageFromAuthority(
        authority: Profile01ConferenceMediaKeyMaterialAuthority.Material,
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ): Profile01MediaKeyPackageBuildResult.Ready {
        val signer = testHostSigner()
        val builder = Profile01MediaKeyPackageBuilder(signer)
        val creationDigest =
            Profile01FactDigest.computeFactDigest(
                Profile01SignedFactEnvelope.parse(Profile01DirectedWireFixtures.creationSignedFactBytes)!!
                    .fullCanonicalBytes,
            )
        val request =
            Profile01MediaKeyPackageBuildRequest(
                material =
                    Profile01ConferenceKeyMaterial(
                        conferenceId = conferenceIdHex.hexToId128Bytes(),
                        conferenceEpoch = conferenceEpoch,
                        ownerModuleId = hostModuleId,
                        membershipVersion = membershipVersion,
                        mediaKeyEpoch = mediaKeyEpoch,
                        conferenceMediaSecret = authority.conferenceMediaSecret.copyOf(),
                        membershipKeyContextDigest = authority.membershipKeyContextDigest.copyOf(),
                        mediaKeyCommitment = authority.mediaKeyCommitment.copyOf(),
                    ),
                creationFactDigest = creationDigest.copyOf(),
                recipient =
                    Profile01PackageRecipientBinding(
                        recipientModuleId = peerModuleId,
                        establishmentKeyVersion = Profile01Q5EmbeddedVectors.RECIPIENT_KEY_VERSION,
                        establishmentPublicKeySpki =
                            Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki(),
                    ),
            )
        return builder.build(request) as Profile01MediaKeyPackageBuildResult.Ready
    }

    private fun decryptPackage(built: Profile01MediaKeyPackageBuildResult.Ready): MediaKeyContextBytes {
        val decrypt =
            Profile01MediaKeyPackageDecryptSeam(
                Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam(),
            )
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(built.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        val result =
            decrypt.decrypt(
                wire,
                peerModuleId,
                Profile01Q5EmbeddedVectors.RECIPIENT_KEY_VERSION,
            )
        require(result is Profile01MediaKeyPackageDecryptResult.Ready)
        return MediaKeyContextBytes(
            masterKey = result.material.masterKey.copyOf(),
            masterSalt = result.material.masterSalt.copyOf(),
            keyContextHint64 = result.material.keyContextHint64.copyOf(),
        )
    }

    private fun hostEncrypt(
        sourceIdentity: String,
        ssrc: Int,
        context: MediaKeyContextBytes,
    ): ConferenceWireEgress.EgressResult.Protected {
        val egress =
            SourceScopedSrtpEgress(
                sourceIdentity = sourceIdentity,
                masterKey = context.masterKey.copyOf(),
                masterSalt = context.masterSalt.copyOf(),
                ssrc = ssrc,
                roc = 0,
                initialSeq = 0x1001,
                headerHeTemplate = SourceScopedSrtpEgress.buildHeaderHeTemplate(ssrc),
            )
        val opus = ByteArray(72) { (it and 0xff).toByte() }
        return egress.protectNext(opus) as ConferenceWireEgress.EgressResult.Protected
    }

    private fun peerEncrypt(
        sourceIdentity: String,
        ssrc: Int,
        context: MediaKeyContextBytes,
    ): ConferenceWireEgress.EgressResult.Protected = hostEncrypt(sourceIdentity, ssrc, context)

    private fun testHostSigner(): Profile01PersistedSignedFactSigner {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        return Profile01PersistedSignedFactSigner.fromPkcs8(
            pkcs8PrivateKey = keyPair.private.encoded,
            signerModuleId = hostModuleId,
            signerKeyVersion = 1L,
        )!!
    }

    private fun harnessNetworkInterfaceName(): String {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
        return candidates
            .firstOrNull { it.isUp && it.supportsMulticast() && !it.isLoopback }
            ?.name
            ?: candidates.firstOrNull { it.isUp && !it.isLoopback }?.name
            ?: "lo"
    }
}
