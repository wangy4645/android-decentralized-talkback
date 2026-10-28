package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorWireFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult
import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import com.talkback.core.conference.session.profile01.Profile01MembershipIngressResult
import com.talkback.core.conference.session.profile01.Profile01SessionEndpointAuthority
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01ValidationResult
import com.talkback.core.conference.session.profile01.Profile01WireMembershipFact
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.SessionHeadSyncOutcome
import com.talkback.core.conference.session.profile01.wire.Profile01CborCodec
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipIncarnationAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelopeBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01SourceDeclarationAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborEncoder
import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2-R — field-isomorphic desk for catalog / UNKNOWN_SSRC residual closure.
 *
 * Evidence: `talkback/logs/issue-a-env-closure-field-20260923-112605/`
 * session `52247d33-2191-47c3-b1bb-22953c3b418f`.
 */
class Profile01A2rCatalogResidualClosureDeskTest {
    private val sessionId = A2R_FIELD_SESSION_ID
    private val channelId = Profile01GoldenVectorWireFixtures.CHANNEL_ID
    private val conferenceIdHex = Profile01GoldenVectorWireFixtures.CONFERENCE_ID
    private val conferenceIdBytes = conferenceIdHex.hexToId128Bytes()

    private val membershipWire = Profile01GoldenVectorWireFixtures.decodeMembershipWireFact()
    private val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()

    private val fieldM04SourceGeneration = 2L
    private val fieldM04Ssrc = 852_829_741

    private lateinit var registry: ConferenceSessionMediaControlFactRegistry
    private lateinit var ingress: Profile01ConferenceMediaFactIngress
    private lateinit var validator: Profile01ConferenceMediaFactValidator
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var supplementRegistry: Profile01SessionMediaSupplementRegistry
    private lateinit var wireIngress: MeetingProfile01FactWireIngress
    private lateinit var trustKeys: MutableMap<Pair<String, Long>, ByteArray>
    private lateinit var m04Signer: ModuleSigner

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        registry = ConferenceSessionMediaControlFactRegistry()
        supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)

        m04Signer = testSigner("M04")
        trustKeys =
            buildMap {
                putAll(goldenPublicKeys())
            }.toMutableMap()

        validator =
            Profile01ConferenceMediaFactValidator(
                Profile01GoldenVectorSignedFactTrustBoundary(trustKeys),
            )
        ingress =
            Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge = bridge,
                supplementRegistry = supplementRegistry,
            )
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, conferenceIdHex)

        val factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        val materializer =
            Profile01ShadowMemberBindingMaterializer(
                factPort = factPort,
                ingress = ingress,
                localModuleId = { "M01" },
                readSignedLocalSource = { null },
                retryLocalSourceBuild = {},
            )
        wireIngress =
            MeetingProfile01FactWireIngress(
                ingress = ingress,
                supplementRegistry = supplementRegistry,
                sessionIndex = sessionIndex,
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { "M01" },
                localEstablishmentKeyVersion = { 1L },
                memberBindingMaterializer = materializer,
            )

        bootstrapHostMembershipHead()
    }

    @After
    fun tearDown() {
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun r1_unknownSignerBeforeTrustMaterialized_recoversCatalogOnRefresh() {
        seedM02AndM03Catalog()
        val head = applyMembershipIncludingM04()
        sessionIndex.markMediaConnected(sessionId, "M04")

        val m04Signed =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                membershipVersion = head.membershipVersion,
                mediaKeyEpoch = head.mediaKeyEpoch,
            )
        assertEquals(WireIngressOutcome.REJECTED_PUBLISH, wire(m04Signed, "M04"))
        assertNull(registry.member(conferenceIdHex, "M04"))
        assertEquals(1, wireIngress.peerSourceDeclarationContinuityForTest().stagedCount(conferenceIdHex))

        trustKeys.putAll(m04Signer.publicKeys)
        wireIngress.refreshPeerSourceDeclarationContinuityIfReady(sessionId)

        assertEquals("M04", registry.member(conferenceIdHex, "M04")?.moduleId)
        assertEquals(0, wireIngress.peerSourceDeclarationContinuityForTest().stagedCount(conferenceIdHex))
    }

    @Test
    fun r3_staleMemberContextThenHead_recoversAuthoritativeCatalog() {
        seedM02AndM03Catalog()
        sessionIndex.markMediaConnected(sessionId, "M04")

        val staleContext =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                membershipVersion = membershipWire.membershipVersion,
                mediaKeyEpoch = 1L,
            )
        assertEquals(WireIngressOutcome.REJECTED_PUBLISH, wire(staleContext, "M04"))
        assertEquals(1, wireIngress.peerSourceDeclarationContinuityForTest().stagedCount(conferenceIdHex))

        val head = applyMembershipIncludingM04()
        trustKeys.putAll(m04Signer.publicKeys)
        wireIngress.refreshPeerSourceDeclarationContinuityIfReady(sessionId)
        assertNull(registry.member(conferenceIdHex, "M04"))

        val authoritativeAtHead =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                membershipVersion = head.membershipVersion,
                mediaKeyEpoch = head.mediaKeyEpoch,
            )
        assertEquals(WireIngressOutcome.APPLIED, wire(authoritativeAtHead, "M04"))
        assertEquals("M04", registry.member(conferenceIdHex, "M04")?.moduleId)
    }

    private fun bootstrapHostMembershipHead() {
        seedSupplement(sessionWire.mediaKeyEpoch, sessionWire.membershipVersion)
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            ingress.ingestSession(sessionWire, Slice4MulticastNetworkConstants.DEFAULT_IFACE).publishOutcome,
        )
        val membership =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(membership is Profile01MembershipIngressResult.Converged)

        seedSupplement(membershipWire.mediaKeyEpoch, membershipWire.membershipVersion)
        assertEquals(
            SessionHeadSyncOutcome.APPLIED,
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
                endpointAuthority =
                    Profile01SessionEndpointAuthority.InitialCreationContext(
                        Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                    ),
            ),
        )

        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionId,
            channelId,
            membershipWire.membershipVersion,
        )
        sessionIndex.updateRosterEpoch(sessionId, membershipWire.membershipVersion)
    }

    private fun seedM02AndM03Catalog() {
        sessionIndex.markMediaConnected(sessionId, "M02")
        sessionIndex.markMediaConnected(sessionId, "M03")

        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            ingress.ingestMember(Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact()).publishOutcome,
        )
        assertEquals(
            WireIngressOutcome.APPLIED,
            wireIngress.onConferenceSignedFact(
                signedFactEnvelope(
                    Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes,
                    fromModuleId = "M02",
                ),
            ),
        )
    }

    private fun applyMembershipIncludingM04(): Profile01MembershipGeneration {
        val current =
            validator.membershipRegistry().current(conferenceIdHex)
                ?: error("membership head missing")
        val bumpedMembership =
            Profile01WireMembershipFact(
                signedFactBytes = byteArrayOf(),
                factDigest = ByteArray(32) { (it + 0xA4).toByte() },
                conferenceId = conferenceIdHex,
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
        val head = (applied as Profile01MembershipApplyResult.Accepted).generation
        assertEquals(
            SessionHeadSyncOutcome.APPLIED,
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
                endpointAuthority = Profile01SessionEndpointAuthority.ExistingPublishedSession,
            ),
        )
        sessionIndex.updateRosterEpoch(sessionId, head.membershipVersion)
        wireIngress.peerSourceDeclarationContinuityForTest().reconcileMembershipHead(conferenceIdHex, head)
        return head
    }

    private fun wire(
        signedBytes: ByteArray,
        fromModuleId: String,
    ): WireIngressOutcome =
        wireIngress.onConferenceSignedFact(signedFactEnvelope(signedBytes, fromModuleId = fromModuleId))

    private fun signedSourceDeclaration(
        moduleId: String,
        sourceGeneration: Long,
        ssrc: Int,
        membershipVersion: Long = membershipWire.membershipVersion,
        mediaKeyEpoch: Long = membershipWire.mediaKeyEpoch,
    ): ByteArray {
        val signer = if (moduleId == "M04") m04Signer else testSigner(moduleId)
        val instanceId =
            Profile01MembershipIncarnationAuthority.deriveIncarnationId128(conferenceIdBytes, moduleId)
        val snapshot =
            Profile01SourceDeclarationAuthoritySnapshot(
                conferenceId = conferenceIdBytes,
                conferenceEpoch = membershipWire.conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                declaringModuleId = moduleId,
                sourceGeneration = sourceGeneration,
                sourceInstanceId = instanceId,
                ssrc = ssrc,
                mediaGroupDescriptorDigest = goldenDescriptorDigest(),
                signerKeyVersion = 1L,
            )
        val full = Profile01WireCborEncoder.encodeSourceDeclarationFullFact(snapshot)
        return Profile01SignedFactEnvelopeBuilder.build(full, signer.signer.signFullFact(full))
    }

    private fun goldenDescriptorDigest(): ByteArray {
        val envelope =
            Profile01WireCborDecoder.parseEnvelope(
                Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes,
            )!!
        val authority =
            Profile01CborCodec.decodeStrict(envelope.fullCanonicalBytes).intKeyMap()!![2]!!.intKeyMap()!!
        return authority[9]!!.asByteString()!!.copyOf()
    }

    private fun goldenPublicKeys(): Map<Pair<String, Long>, ByteArray> =
        mapOf(
            "M01" to 1L to hex(GOLDEN_M01_PUBLIC_X963_HEX),
            "M02" to 1L to hex(GOLDEN_M02_PUBLIC_X963_HEX),
        )

    private fun seedSupplement(
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ) {
        val base = Phase1MediaHarness.masterKey.copyOf()
        if (mediaKeyEpoch > 1L) {
            base[0] = (base[0] + mediaKeyEpoch.toInt()).toByte()
        }
        supplementRegistry.put(
            Profile01DerivedMediaKeyMaterial(
                conferenceId = conferenceIdHex,
                conferenceEpoch = membershipWire.conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                masterKey = base,
                masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
                keyContextHint64 = Phase1MediaHarness.keyContextHint64.copyOf(),
                membershipKeyContextDigest = ByteArray(32),
            ),
        )
    }

    private fun signedFactEnvelope(
        bytes: ByteArray,
        fromModuleId: String,
    ): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId(fromModuleId), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = Base64.getEncoder().encodeToString(bytes),
            nonce = "a2r-desk-nonce",
            signature = "a2r-desk-signature",
        )

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
        private const val A2R_FIELD_SESSION_ID = "52247d33-2191-47c3-b1bb-22953c3b418f"

        private const val GOLDEN_M01_PUBLIC_X963_HEX =
            "04984225585d2285c138033d6140e3cef8b91859704e53c313f8b636ba4f9676499734144f46fd19a767a545287c4396b97b69dd38faaea8981adc1a4fed9b401e"
        private const val GOLDEN_M02_PUBLIC_X963_HEX =
            "04cbbcaf7e67f163b45f766bed945666932e4629e1e228afbfc8ece2a8a8cb9eb2ae522dd528a32b21a611569f914b5a79d9afe1c8f438ba987da3037e0ec8b3e7"
    }
}
