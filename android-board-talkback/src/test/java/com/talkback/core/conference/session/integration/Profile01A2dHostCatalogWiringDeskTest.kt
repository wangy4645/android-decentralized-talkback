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
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A2d host catalog wiring — desk GREEN (session 98ea8871…, M01 host). */
class Profile01A2dHostCatalogWiringDeskTest {
    private val sessionId = A2D_FIELD_SESSION_ID
    private val channelId = Profile01GoldenVectorWireFixtures.CHANNEL_ID
    private val conferenceIdHex = Profile01GoldenVectorWireFixtures.CONFERENCE_ID
    private val conferenceIdBytes = conferenceIdHex.hexToId128Bytes()

    private val membershipWire = Profile01GoldenVectorWireFixtures.decodeMembershipWireFact()
    private val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()

    private val fieldM03SourceGeneration = 2L
    private val fieldM03Ssrc = 1_692_464_550
    private val fieldM04SourceGeneration = 2L
    private val fieldM04Ssrc = -2_011_953_613

    private lateinit var registry: ConferenceSessionMediaControlFactRegistry
    private lateinit var ingress: Profile01ConferenceMediaFactIngress
    private lateinit var validator: Profile01ConferenceMediaFactValidator
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var supplementRegistry: Profile01SessionMediaSupplementRegistry
    private lateinit var wireIngress: MeetingProfile01FactWireIngress
    private lateinit var signers: Map<String, ModuleSigner>

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        registry = ConferenceSessionMediaControlFactRegistry()
        supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)

        signers =
            mapOf(
                "M03" to testSigner("M03"),
                "M04" to testSigner("M04"),
            )
        validator =
            Profile01ConferenceMediaFactValidator(
                Profile01GoldenVectorSignedFactTrustBoundary(
                    buildMap {
                        putAll(goldenPublicKeys())
                        signers.values.forEach { putAll(it.publicKeys) }
                    },
                ),
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
    fun g1_prematureBundledDeclaration_remainsRejected() {
        seedM02AndM03Catalog()
        sessionIndex.markMediaConnected(sessionId, "M04")

        val bundled =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
            )
        assertEquals(
            MeetingProfile01PeerSourceDeclarationContinuity.MEMBER_NOT_IN_ROSTER,
            rejectionReason(validator.validateMember(decodeMemberWire(bundled))),
        )
        assertEquals(WireIngressOutcome.REJECTED_PUBLISH, wire(bundled, "M04"))
        assertNull(registry.member(conferenceIdHex, "M04"))
        assertEquals(1, wireIngress.peerSourceDeclarationContinuityForTest().stagedCount(conferenceIdHex))
    }

    @Test
    fun g3_authoritativeSourceAfterMembershipHead_recoversCatalog() {
        seedM02AndM03Catalog()
        sessionIndex.markMediaConnected(sessionId, "M04")

        val bundled =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
            )
        assertEquals(WireIngressOutcome.REJECTED_PUBLISH, wire(bundled, "M04"))

        val authoritativePremature =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                sourceInstanceSalt = 1,
            )
        assertEquals(WireIngressOutcome.REJECTED_PUBLISH, wire(authoritativePremature, "M04"))
        assertNull(registry.member(conferenceIdHex, "M04"))

        val head = applyMembershipIncludingM04()
        assertTrue(ingress.isSessionControlFactAtMembershipHead(conferenceIdHex, channelId))
        wireIngress.refreshPeerSourceDeclarationContinuityForTest(sessionId)
        assertNull(registry.member(conferenceIdHex, "M04"))

        val authoritativeAtHead =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                sourceInstanceSalt = 1,
                membershipVersion = head.membershipVersion,
                mediaKeyEpoch = head.mediaKeyEpoch,
            )
        assertTrue(
            validator.validateMember(decodeMemberWire(authoritativeAtHead)) is Profile01ValidationResult.ReadyMember,
        )
        assertEquals(WireIngressOutcome.APPLIED, wire(authoritativeAtHead, "M04"))
        assertEquals("M04", registry.member(conferenceIdHex, "M04")?.moduleId)
        assertTrue((wiring.runtimeSnapshot(sessionId)?.catalogEntries ?: 0) >= 3)
    }

    @Test
    fun g4_membershipReadyBeforeAuthoritativeSource_appliesDirectly() {
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
        assertEquals(WireIngressOutcome.APPLIED, wire(m04Signed, "M04"))
        assertEquals("M04", registry.member(conferenceIdHex, "M04")?.moduleId)
    }

    @Test
    fun g5_duplicateContinuityRefresh_idempotent() {
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
        assertEquals(WireIngressOutcome.APPLIED, wire(m04Signed, "M04"))
        val incarnationBefore = registry.member(conferenceIdHex, "M04")!!.membershipIncarnationId

        wireIngress.refreshPeerSourceDeclarationContinuityForTest(sessionId)
        wireIngress.refreshPeerSourceDeclarationContinuityForTest(sessionId)

        assertEquals(incarnationBefore, registry.member(conferenceIdHex, "M04")!!.membershipIncarnationId)
        assertEquals(0, wireIngress.peerSourceDeclarationContinuityForTest().stagedCount(conferenceIdHex))
    }

    @Test
    fun g6_memberRemovedFromMembershipHead_noResurrection() {
        seedM02AndM03Catalog()
        sessionIndex.markMediaConnected(sessionId, "M04")
        assertEquals(
            WireIngressOutcome.REJECTED_PUBLISH,
            wire(
                signedSourceDeclaration(
                    moduleId = "M04",
                    sourceGeneration = fieldM04SourceGeneration,
                    ssrc = fieldM04Ssrc,
                    sourceInstanceSalt = 1,
                ),
                "M04",
            ),
        )

        val withM04 = applyMembershipIncludingM04()
        assertTrue(validator.membershipRegistry().current(conferenceIdHex)!!.hasMember("M04"))
        assertNull(registry.member(conferenceIdHex, "M04"))

        val withoutM04 =
            validator.membershipRegistry().current(conferenceIdHex)!!.members
                .filter { it.moduleId != "M04" }
        val shrunk =
            Profile01WireMembershipFact(
                signedFactBytes = byteArrayOf(),
                factDigest = ByteArray(32) { (it + 0xB6).toByte() },
                conferenceId = conferenceIdHex,
                conferenceEpoch = withM04.conferenceEpoch,
                ownerModuleId = membershipWire.ownerModuleId,
                membershipVersion = withM04.membershipVersion + 1L,
                previousMembershipDigest = withM04.generationFactDigest.copyOf(),
                members = withoutM04,
                mediaKeyEpoch = withM04.mediaKeyEpoch,
                mediaKeyCommitment = withM04.mediaKeyCommitment.copyOf(),
            )
        assertTrue(
            validator.membershipRegistry().applyMembership(shrunk) is Profile01MembershipApplyResult.Accepted,
        )
        val shrunkHead = validator.membershipRegistry().current(conferenceIdHex)!!
        wireIngress.peerSourceDeclarationContinuityForTest().reconcileMembershipHead(
            conferenceIdHex,
            shrunkHead,
        )
        assertFalse(shrunkHead.hasMember("M04"))
        assertEquals(0, wireIngress.peerSourceDeclarationContinuityForTest().stagedCount(conferenceIdHex))

        wireIngress.refreshPeerSourceDeclarationContinuityForTest(sessionId)
        assertNull(registry.member(conferenceIdHex, "M04"))

        val removedMemberSource =
            signedSourceDeclaration(
                moduleId = "M04",
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                sourceInstanceSalt = 1,
                membershipVersion = shrunkHead.membershipVersion,
                mediaKeyEpoch = shrunkHead.mediaKeyEpoch,
            )
        assertEquals(WireIngressOutcome.REJECTED_PUBLISH, wire(removedMemberSource, "M04"))
        wireIngress.refreshPeerSourceDeclarationContinuityForTest(sessionId)
        assertNull(registry.member(conferenceIdHex, "M04"))
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

        val m03Signed =
            signedSourceDeclaration(
                moduleId = "M03",
                sourceGeneration = fieldM03SourceGeneration,
                ssrc = fieldM03Ssrc,
            )
        assertEquals(WireIngressOutcome.APPLIED, wire(m03Signed, "M03"))
        assertEquals("M03", registry.member(conferenceIdHex, "M03")?.moduleId)
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
        sourceInstanceSalt: Int = 0,
        membershipVersion: Long = membershipWire.membershipVersion,
        mediaKeyEpoch: Long = membershipWire.mediaKeyEpoch,
    ): ByteArray {
        val signer = signers.getValue(moduleId)
        val instanceId =
            Profile01MembershipIncarnationAuthority.deriveIncarnationId128(conferenceIdBytes, moduleId)
        if (sourceInstanceSalt != 0) {
            instanceId[15] = (instanceId[15].toInt() xor sourceInstanceSalt).toByte()
        }
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

    private fun decodeMemberWire(signedBytes: ByteArray) =
        (
            Profile01WireCborDecoder.decodeSourceDeclarationMember(signedBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready
        ).value

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
            nonce = "a2d-desk-nonce",
            signature = "a2d-desk-signature",
        )

    private fun rejectionReason(validation: Profile01ValidationResult): String =
        when (validation) {
            is Profile01ValidationResult.Invalid -> validation.reason
            is Profile01ValidationResult.NotPublished -> validation.reason
            is Profile01ValidationResult.ReadyMember -> error("expected rejection, got ReadyMember")
            else -> error("unexpected validation: $validation")
        }

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
        private const val A2D_FIELD_SESSION_ID = "98ea8871-7b8f-422e-b8cd-73d48e51fe9c"

        private const val GOLDEN_M01_PUBLIC_X963_HEX =
            "04984225585d2285c138033d6140e3cef8b91859704e53c313f8b636ba4f9676499734144f46fd19a767a545287c4396b97b69dd38faaea8981adc1a4fed9b401e"
        private const val GOLDEN_M02_PUBLIC_X963_HEX =
            "04cbbcaf7e67f163b45f766bed945666932e4629e1e228afbfc8ece2a8a8cb9eb2ae522dd528a32b21a611569f914b5a79d9afe1c8f438ba987da3037e0ec8b3e7"
    }
}
