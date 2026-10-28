package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorWireFixtures
import com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult
import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import com.talkback.core.conference.session.profile01.Profile01ProductionSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01ProfileBackedModuleTrustLookup
import com.talkback.core.conference.session.profile01.Profile01SessionEndpointAuthority
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.SessionHeadSyncOutcome
import com.talkback.core.conference.session.profile01.wire.Profile01CborCodec
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipIncarnationAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelopeBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01SourceDeclarationAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborEncoder
import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.session.gbc.crypto.GenerationFactTestSigning
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.FixtureProfileRevisionAuthenticator
import com.talkback.core.session.gbc.trust.profile.GenerationFactProfileTrustPayload
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptResult
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptor
import com.talkback.core.conference.session.profile01.Profile01WireMembershipFact
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2-R signing-trust RCA desk — production [Profile01ProfileBackedModuleTrustLookup] path.
 *
 * Field evidence: `talkback/logs/issue-a-a2r-field-20260923-123856/` — M04 UNKNOWN_SIGNER_KEY
 * while M02/M03 APPLIED; FTPH bindings omitted M04 (`FieldTestProvisioningSupport.MODULE_IDS`).
 */
class Profile01A2rSigningTrustAvailabilityDeskTest {
    private val sessionId = A2R_FIELD_SESSION_ID
    private val channelId = Profile01GoldenVectorWireFixtures.CHANNEL_ID
    private val conferenceIdHex = Profile01GoldenVectorWireFixtures.CONFERENCE_ID
    private val conferenceIdBytes = conferenceIdHex.hexToId128Bytes()

    private val membershipWire = Profile01GoldenVectorWireFixtures.decodeMembershipWireFact()
    private val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()

    private val fieldM04SourceGeneration = 2L
    private val fieldM04Ssrc = 852_829_741
    private val signerKeyVersion = 1L
    private val taskProfileRevision = 10L

    private lateinit var trustStore: AcceptedLocalTrustStateStore
    private lateinit var profileAcceptor: ProfileRevisionAcceptor
    private lateinit var profileAuthenticator: FixtureProfileRevisionAuthenticator
    private lateinit var registry: ConferenceSessionMediaControlFactRegistry
    private lateinit var ingress: Profile01ConferenceMediaFactIngress
    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var supplementRegistry: Profile01SessionMediaSupplementRegistry
    private lateinit var wireIngress: MeetingProfile01FactWireIngress
    private lateinit var m03Signer: ModuleSigner
    private lateinit var m04Signer: ModuleSigner
    private var continuityRefreshCount = 0

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        continuityRefreshCount = 0

        m03Signer = generationFactTestModuleSigner("M03")
        m04Signer = ephemeralModuleSigner("M04")

        trustStore = AcceptedLocalTrustStateStore()
        profileAuthenticator = FixtureProfileRevisionAuthenticator()
        profileAcceptor = ProfileRevisionAcceptor(profileAuthenticator, trustStore)
        trustStore.onPeerSigningTrustModulesMaterialized = { continuityRefreshCount += it.size }

        seedSigningTrust(modules = setOf("M02", "M03"), includeM04 = false)

        registry = ConferenceSessionMediaControlFactRegistry()
        supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val validator =
            Profile01ConferenceMediaFactValidator(
                Profile01ProductionSignedFactTrustBoundary(
                    Profile01ProfileBackedModuleTrustLookup(trustStore),
                ),
            )
        ingress =
            Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge = bridge,
                supplementRegistry = supplementRegistry,
            )
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
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
        sessionIndex.markMediaConnected(sessionId, "M04")
    }

    @After
    fun tearDown() {
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun r1_m04MissingFromSigningTrust_stagedThenRecoversOnAtomicReplace() {
        seedM03Catalog()
        val head = applyMembershipIncludingM04()

        val m04Signed =
            signedSourceDeclaration(
                moduleId = "M04",
                signer = m04Signer,
                sourceGeneration = fieldM04SourceGeneration,
                ssrc = fieldM04Ssrc,
                membershipVersion = head.membershipVersion,
                mediaKeyEpoch = head.mediaKeyEpoch,
            )
        assertEquals(WireIngressOutcome.REJECTED_PUBLISH, wire(m04Signed, "M04"))
        assertNull(registry.member(conferenceIdHex, "M04"))
        assertEquals(1, wireIngress.peerSourceDeclarationContinuityForTest().stagedCount(conferenceIdHex))

        materializeM04SigningTrust()

        assertEquals("M04", registry.member(conferenceIdHex, "M04")?.moduleId)
        assertEquals(0, wireIngress.peerSourceDeclarationContinuityForTest().stagedCount(conferenceIdHex))
        assertTrue(continuityRefreshCount >= 1)
    }

    private fun materializeM04SigningTrust() {
        val current = trustStore.currentSnapshot() ?: error("trust snapshot missing")
        val payload =
            GenerationFactProfileTrustPayload(
                taskProfileRevision = taskProfileRevision + 1,
                localModuleId = "M01",
                moduleBindings =
                    current.bindingsByKey.values.toList() +
                        ModuleSigningBinding(
                            moduleId = "M04",
                            signerKeyVersion = signerKeyVersion,
                            keyState = GenerationFactKeyState.ACTIVE,
                            publicKeySpki = m04Signer.publicKeySpki.copyOf(),
                            activatedAtRevision = taskProfileRevision + 1,
                        ),
            )
        profileAuthenticator.allowPayload(payload)
        when (val accepted = profileAcceptor.acceptPayload(payload)) {
            is ProfileRevisionAcceptResult.Accepted -> Unit
            else -> error("unexpected accept result: $accepted")
        }
        wireIngress.refreshPeerSourceDeclarationContinuityIfReady(sessionId)
    }

    private fun seedSigningTrust(
        modules: Set<String>,
        includeM04: Boolean,
    ) {
        val effectiveModules = if (includeM04) modules + "M04" else modules
        val payload =
            GenerationFactProfileTrustPayload(
                taskProfileRevision = taskProfileRevision,
                localModuleId = "M01",
                moduleBindings =
                    effectiveModules.map { moduleId ->
                        ModuleSigningBinding(
                            moduleId = moduleId,
                            signerKeyVersion = signerKeyVersion,
                            keyState = GenerationFactKeyState.ACTIVE,
                            publicKeySpki =
                                when (moduleId) {
                                    "M04" -> m04Signer.publicKeySpki.copyOf()
                                    "M03" -> m03Signer.publicKeySpki.copyOf()
                                    else -> GenerationFactTestSigning.publicKeySpki.copyOf()
                                },
                            activatedAtRevision = taskProfileRevision,
                        )
                    },
            )
        profileAuthenticator.allowPayload(payload)
        assertTrue(profileAcceptor.acceptPayload(payload) is ProfileRevisionAcceptResult.Accepted)
    }

    private fun applyMembershipIncludingM04(): Profile01MembershipGeneration {
        val current =
            ingress.membershipRegistry().current(conferenceIdHex)
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
        val applied = ingress.membershipRegistry().applyMembership(bumpedMembership)
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

    private fun seedM03Catalog() {
        sessionIndex.markMediaConnected(sessionId, "M03")
        val m03Signed =
            signedSourceDeclaration(
                moduleId = "M03",
                signer = m03Signer,
                sourceGeneration = 2L,
                ssrc = -1_278_842_490,
            )
        assertEquals(WireIngressOutcome.APPLIED, wire(m03Signed, "M03"))
    }

    private fun bootstrapHostMembershipHead() {
        seedSupplement(sessionWire.mediaKeyEpoch, sessionWire.membershipVersion)
        val creationApplied = ingress.membershipRegistry().seedFromCreation(sessionWire)
        assertTrue(
            creationApplied is Profile01MembershipApplyResult.Accepted ||
                creationApplied is Profile01MembershipApplyResult.Idempotent,
        )

        seedSupplement(membershipWire.mediaKeyEpoch, membershipWire.membershipVersion)
        val applied = ingress.membershipRegistry().applyMembership(membershipWire)
        assertTrue(applied is Profile01MembershipApplyResult.Accepted)
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

    private fun wire(
        signedBytes: ByteArray,
        fromModuleId: String,
    ): WireIngressOutcome =
        wireIngress.onConferenceSignedFact(signedFactEnvelope(signedBytes, fromModuleId))

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
            nonce = "a2r-signing-trust-nonce",
            signature = "a2r-signing-trust-signature",
        )

    private fun signedSourceDeclaration(
        moduleId: String,
        signer: ModuleSigner,
        sourceGeneration: Long,
        ssrc: Int,
        membershipVersion: Long = membershipWire.membershipVersion,
        mediaKeyEpoch: Long = membershipWire.mediaKeyEpoch,
    ): ByteArray {
        val snapshot =
            Profile01SourceDeclarationAuthoritySnapshot(
                conferenceId = conferenceIdBytes,
                conferenceEpoch = membershipWire.conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                declaringModuleId = moduleId,
                sourceGeneration = sourceGeneration,
                sourceInstanceId =
                    Profile01MembershipIncarnationAuthority.deriveIncarnationId128(
                        conferenceIdBytes,
                        moduleId,
                    ),
                ssrc = ssrc,
                mediaGroupDescriptorDigest = goldenDescriptorDigest(),
                signerKeyVersion = signerKeyVersion,
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

    private fun seedSupplement(
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ) {
        supplementRegistry.put(
            Profile01DerivedMediaKeyMaterial(
                conferenceId = conferenceIdHex,
                conferenceEpoch = membershipWire.conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                masterKey = Phase1MediaHarness.masterKey.copyOf(),
                masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
                keyContextHint64 = Phase1MediaHarness.keyContextHint64.copyOf(),
                membershipKeyContextDigest = ByteArray(32),
            ),
        )
    }

    private fun generationFactTestModuleSigner(moduleId: String): ModuleSigner {
        val signer =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = GENERATION_FACT_TEST_PKCS8,
                signerModuleId = moduleId,
                signerKeyVersion = signerKeyVersion,
            )!!
        return ModuleSigner(signer, GenerationFactTestSigning.publicKeySpki.copyOf())
    }

    private fun ephemeralModuleSigner(moduleId: String): ModuleSigner {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        return ModuleSigner(
            signer =
                Profile01PersistedSignedFactSigner.fromPkcs8(
                    pkcs8PrivateKey = keyPair.private.encoded,
                    signerModuleId = moduleId,
                    signerKeyVersion = signerKeyVersion,
                )!!,
            publicKeySpki = keyPair.public.encoded,
        )
    }

    private data class ModuleSigner(
        val signer: Profile01PersistedSignedFactSigner,
        val publicKeySpki: ByteArray,
    )

    companion object {
        private const val A2R_FIELD_SESSION_ID = "52247d33-2191-47c3-b1bb-22953c3b418f"

        private val GENERATION_FACT_TEST_PKCS8: ByteArray =
            hex(
                "3041020100301306072a8648ce3d020106082a8648ce3d030107042730250201010420" +
                    "241ed51cb84c1723036d52794fc9c7298de352deafa31bd3f438a9066ecee34a",
            )

        private fun hex(value: String): ByteArray {
            val clean = value.trim()
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                val idx = i * 2
                out[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
            }
            return out
        }
    }
}
