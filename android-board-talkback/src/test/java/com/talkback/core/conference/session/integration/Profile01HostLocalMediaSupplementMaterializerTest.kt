package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaGroupDescriptor
import com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipIncarnationAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01PackageCrypto
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.wire.Profile01P1DProductionIngressHarness
import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01HostLocalMediaSupplementMaterializerTest {
    private val sessionId = "a1-session-1"
    private val channelId = Profile01DirectedWireFixtures.CHANNEL_ID
    private val conferenceIdHex = Profile01DirectedWireFixtures.CONFERENCE_ID
    private val conferenceEpoch = 7L
    private val membershipVersion = 0L
    private val mediaKeyEpoch = 1L

    private val sessionIndex = MeetingProfile01ConferenceSessionIndex()
    private val mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
    private val supplementRegistry = Profile01SessionMediaSupplementRegistry()
    private val controlRegistry = ConferenceSessionMediaControlFactRegistry()
    private val readyCallbacks = AtomicInteger(0)
    private val logs = mutableListOf<String>()

    private lateinit var materializer: Profile01HostLocalMediaSupplementMaterializer
    private lateinit var authorityMaterial: Profile01ConferenceMediaKeyMaterialAuthority.Material
    private lateinit var factPort: MeetingProfile01AwareConferenceSessionMediaFactPort

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(controlRegistry, sessionIndex)
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, conferenceIdHex)
        authorityMaterial = ensureAuthorityMaterial()
        materializer =
            Profile01HostLocalMediaSupplementMaterializer(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                supplementRegistry = supplementRegistry,
                onSupplementReady = { _, _ -> readyCallbacks.incrementAndGet() },
                onLog = { logs.add(it) },
            )
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun a1U1_hostMaterial_registryHit_projectionApplied() {
        assertEquals(
            HostLocalSupplementMaterializationOutcome.READY,
            materialize(),
        )
        val derived = supplementRegistry.lookup(conferenceIdHex, mediaKeyEpoch)
        assertNotNull(derived)
        assertTrue(logs.any { it.contains("PROFILE01_HOST_LOCAL_SUPPLEMENT") && it.contains("outcome=READY") })
        assertEquals(1, readyCallbacks.get())

        seedFixtureAlignedSupplement()
        val hostProjection = buildHostProjectionWithFixtureSupplement()
        assertEquals(HostSessionProjectionOutcome.APPLIED, hostProjection.maybeProjectHostShadowSession(sessionId))
        assertTrue(ConferenceSessionMediaBridge.hasSession(sessionId))
    }

    @Test
    fun a1U1_semanticEquivalence_matchesPackageCryptoDerivation() {
        materialize()
        val hostDerived = supplementRegistry.lookup(conferenceIdHex, mediaKeyEpoch)!!
        val conferenceId = conferenceIdHex.hexToId128Bytes()
        val srtp =
            Profile01PackageCrypto.deriveSrtpMaterial(
                authorityMaterial.conferenceMediaSecret,
                authorityMaterial.membershipKeyContextDigest,
            )
        val hint =
            Profile01PackageCrypto.deriveKeyContextHint64(
                conferenceId,
                conferenceEpoch,
                mediaKeyEpoch,
                authorityMaterial.membershipKeyContextDigest,
            )
        assertArrayEquals(srtp.masterKey, hostDerived.masterKey)
        assertArrayEquals(srtp.masterSalt, hostDerived.masterSalt)
        assertArrayEquals(hint, hostDerived.keyContextHint64)
        assertArrayEquals(authorityMaterial.membershipKeyContextDigest, hostDerived.membershipKeyContextDigest)
    }

    @Test
    fun a1U2_deferredBeforeMaterialize_appliedAfterReadyContinuation() {
        val hostProjection = buildHostProjectionWithFixtureSupplement()
        assertEquals(HostSessionProjectionOutcome.DEFERRED, hostProjection.maybeProjectHostShadowSession(sessionId))

        assertEquals(HostLocalSupplementMaterializationOutcome.READY, materialize())
        assertEquals(1, readyCallbacks.get())
        seedFixtureAlignedSupplement()
        assertEquals(HostSessionProjectionOutcome.APPLIED, hostProjection.maybeProjectHostShadowSession(sessionId))
    }

    @Test
    fun a1U3_conferenceBindingMismatch_rejectsWithoutRegistryWrite() {
        sessionIndex.bindConferenceId(sessionId, "00112233445566778899aabbccddeeff")
        assertEquals(
            HostLocalSupplementMaterializationOutcome.STALE_IDENTITY,
            materialize(),
        )
        assertEquals(null, supplementRegistry.lookup(conferenceIdHex, mediaKeyEpoch))
        assertEquals(0, readyCallbacks.get())
        assertTrue(logs.any { it.contains("outcome=STALE_IDENTITY") })
    }

    @Test
    fun a1U3_mediaKeyEpochMismatchOnCommitment_rejects() {
        val wrongCommitment = authorityMaterial.mediaKeyCommitment.copyOf()
        wrongCommitment[0] = (wrongCommitment[0].toInt() xor 0xFF).toByte()
        assertEquals(
            HostLocalSupplementMaterializationOutcome.STALE_IDENTITY,
            materialize(expectedCommitment = wrongCommitment),
        )
        assertEquals(null, supplementRegistry.lookup(conferenceIdHex, mediaKeyEpoch))
    }

    @Test
    fun a1U4_duplicateMaterialize_idempotentRegistryAndCallback() {
        assertEquals(HostLocalSupplementMaterializationOutcome.READY, materialize())
        assertEquals(HostLocalSupplementMaterializationOutcome.ALREADY_PRESENT, materialize())
        assertEquals(2, readyCallbacks.get())

        seedFixtureAlignedSupplement()
        val hostProjection = buildHostProjectionWithFixtureSupplement()
        assertEquals(HostSessionProjectionOutcome.APPLIED, hostProjection.maybeProjectHostShadowSession(sessionId))
        assertEquals(HostSessionProjectionOutcome.ALREADY_LIVE, hostProjection.maybeProjectHostShadowSession(sessionId))
    }

    @Test
    fun a1U5_peerDecryptPath_unchangedUsesNotifySupplementReady() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val peerReady = AtomicInteger(0)
        fixture.ingress.onMediaSupplementReady = { _, _ -> peerReady.incrementAndGet() }
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value

        val result =
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                signedFactBytes = fixture.builtPackage.signedFactBytes,
                localRecipientModuleId = fixture.localModuleId,
                localEstablishmentKeyVersion = fixture.establishmentKeyVersion,
            )
        assertTrue(result is Profile01MediaKeyPackageIngressResult.Ready)
        assertNotNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
        assertEquals(1, peerReady.get())
        assertTrue(
            fixture.observabilityLogs.any {
                it.startsWith("PROFILE01_MEDIA_SUPPLEMENT ") && it.contains("state=READY")
            },
        )
    }

    private fun materialize(
        expectedCommitment: ByteArray = authorityMaterial.mediaKeyCommitment,
    ): HostLocalSupplementMaterializationOutcome =
        materializer.materializeFromAuthority(
            HostLocalSupplementMaterializationRequest(
                sessionId = sessionId,
                conferenceIdHex = conferenceIdHex,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                expectedMediaKeyCommitment = expectedCommitment.copyOf(),
            ),
        )

    private fun ensureAuthorityMaterial(): Profile01ConferenceMediaKeyMaterialAuthority.Material {
        val conferenceId = conferenceIdHex.hexToId128Bytes()
        val membershipView =
            listOf("M01", "M02").map { moduleId ->
                Profile01WireMembershipMember(
                    moduleId = moduleId,
                    membershipIncarnationId =
                        Profile01MembershipIncarnationAuthority.deriveIncarnationId128(
                            conferenceId = conferenceId,
                            moduleId = moduleId,
                        ),
                )
            }
        val descriptorDigest = Profile01FactDigest.descriptorDigest(Profile01MediaGroupDescriptor.productionDescriptor())
        return mediaKeyAuthority.ensureMaterial(
            sessionId = sessionId,
            conferenceId = conferenceId,
            conferenceEpoch = conferenceEpoch,
            ownerModuleId = "M01",
            mediaGroupDescriptorDigest = descriptorDigest,
            membershipView = membershipView,
            membershipVersion = membershipVersion,
            mediaKeyEpoch = mediaKeyEpoch,
        )
    }

    private fun seedFixtureAlignedSupplement() {
        val corpus = com.talkback.core.conference.session.profile01.wire.Profile01Q5EmbeddedVectors.wirePackage()
        val decrypt =
            com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam(
                com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam(),
            )
        val decrypted =
            decrypt.decrypt(
                corpus,
                com.talkback.core.conference.session.profile01.wire.Profile01Q5EmbeddedVectors.RECIPIENT_MODULE_ID,
                com.talkback.core.conference.session.profile01.wire.Profile01Q5EmbeddedVectors.RECIPIENT_KEY_VERSION,
            )
        require(decrypted is com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult.Ready)
        supplementRegistry.put(
            decrypted.material.copy(
                conferenceId = conferenceIdHex,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
            ),
        )
    }

    private fun buildHostProjectionWithFixtureSupplement(): Profile01HostLocalSessionFactProjection {
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator =
                    Profile01ConferenceMediaFactValidator(
                        Profile01DirectedWireFixtures.goldenVectorTrustBoundary(),
                    ),
                publisherBridge = ConferenceSessionMediaGbcPublisherBridge(controlRegistry),
                supplementRegistry = supplementRegistry,
            )
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
        return Profile01HostLocalSessionFactProjection(
            readSignedCreationFact = { Profile01DirectedWireFixtures.creationSignedFactBytes },
            readSignedSourceFact = { null },
            ingress = ingress,
            supplementRegistry = supplementRegistry,
            sessionIndex = sessionIndex,
            registry = controlRegistry,
            networkInterfaceName = { harnessNetworkInterfaceName() },
        )
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
