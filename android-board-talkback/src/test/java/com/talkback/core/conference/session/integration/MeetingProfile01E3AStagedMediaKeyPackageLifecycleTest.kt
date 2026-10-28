package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.gbc.AuthoritativeConferenceMediaSessionDeclaration
import com.talkback.core.conference.session.gbc.ConferenceMediaDeclarationDisposition
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageContextClass
import com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult
import com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult
import com.talkback.core.conference.session.profile01.Profile01WireMembershipFact
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.Profile01WireSessionFact
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuildRequest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuildResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01PackageRecipientBinding
import com.talkback.core.conference.session.profile01.wire.Profile01P1DProductionIngressHarness
import com.talkback.core.conference.session.profile01.wire.Profile01Q5CryptoVectorFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage
import com.talkback.core.conference.session.profile01.wire.toHexLower
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E3-A contract fixtures A–D, idempotence, field-ordering regression, IG-2 desk baseline. */
class MeetingProfile01E3AStagedMediaKeyPackageLifecycleTest {
    private val sessionId = "e3a-session"
    private val channelId = "e3a-test-channel"
    private val supplementReadyCount = AtomicInteger(0)

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        supplementReadyCount.set(0)
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
    }

    @After
    fun tearDown() {
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun fixtureA_keyBeforeMembership_stagedThenDrainActivatesSupplement() {
        val env = newEnvironment(seedHeadMediaKeyEpoch = 2L, seedHeadMembershipVersion = 1L)
        val epoch3 = env.buildEpoch3Artifacts(includeFourthMember = false)

        val staged =
            env.ingress.ingestMediaKeyPackageSignedFact(
                epoch3.packageBytes,
                env.localModuleId,
                env.establishmentKeyVersion,
            )
        assertTrue("package ingress=$staged", staged is Profile01MediaKeyPackageIngressResult.Staged)
        assertEquals(1, env.stagedContinuation.stagedCount(env.conferenceIdHex))
        assertNull(env.supplementRegistry.lookup(env.conferenceIdHex, epoch3.mediaKeyEpoch))

        applyMembershipAndDrain(env, epoch3.signedMembershipBytes)

        assertEquals(0, env.stagedContinuation.stagedCount(env.conferenceIdHex))
        assertTrue(env.supplementRegistry.lookup(env.conferenceIdHex, epoch3.mediaKeyEpoch) != null)
        assertEquals(1, supplementReadyCount.get())

        val duplicate =
            env.ingress.ingestMediaKeyPackageSignedFact(
                epoch3.packageBytes,
                env.localModuleId,
                env.establishmentKeyVersion,
            )
        assertTrue(duplicate is Profile01MediaKeyPackageIngressResult.Ready)
        assertTrue((duplicate as Profile01MediaKeyPackageIngressResult.Ready).idempotent)
        assertEquals(1, supplementReadyCount.get())
    }

    @Test
    fun fixtureB_membershipBeforeKey_directActiveNoStaging() {
        val env = newEnvironment(seedHeadMediaKeyEpoch = 2L, seedHeadMembershipVersion = 1L)
        val epoch3 = env.buildEpoch3Artifacts(includeFourthMember = false)
        applyMembershipToRegistry(env, epoch3.signedMembershipBytes)

        val result =
            env.ingress.ingestMediaKeyPackageSignedFact(
                epoch3.packageBytes,
                env.localModuleId,
                env.establishmentKeyVersion,
            )
        assertTrue(result is Profile01MediaKeyPackageIngressResult.Ready)
        assertEquals(0, env.stagedContinuation.stagedCount(env.conferenceIdHex))
        assertEquals(1, supplementReadyCount.get())
    }

    @Test
    fun fixtureC_supersededCandidateNeverActivates() {
        val env = newEnvironment(seedHeadMediaKeyEpoch = 2L, seedHeadMembershipVersion = 1L)
        val stagedCandidate = env.buildEpoch3Artifacts(includeFourthMember = false)
        assertTrue(
            env.ingress.ingestMediaKeyPackageSignedFact(
                stagedCandidate.packageBytes,
                env.localModuleId,
                env.establishmentKeyVersion,
            ) is Profile01MediaKeyPackageIngressResult.Staged,
        )

        val acceptedHead = env.buildEpoch3Artifacts(includeFourthMember = true)
        applyMembershipAndDrain(env, acceptedHead.signedMembershipBytes)

        assertEquals(0, env.stagedContinuation.stagedCount(env.conferenceIdHex))
        assertNull(env.supplementRegistry.lookup(env.conferenceIdHex, stagedCandidate.mediaKeyEpoch))
        assertEquals(0, supplementReadyCount.get())
    }

    @Test
    fun fixtureD_staleBranchInvalidNeverStaged() {
        val env = newEnvironment(seedHeadMediaKeyEpoch = 2L, seedHeadMembershipVersion = 1L)
        val epoch3 = env.buildEpoch3Artifacts(includeFourthMember = false)

        val staleBytes =
            Profile01P1DProductionIngressHarness.resignTamperedPackage(
                env.hostSigner,
                epoch3.packageBytes,
            ) { pkg ->
                pkg.copy(membershipVersion = 1L, mediaKeyEpoch = 1L)
            }
        assertTrue(
            env.ingress.ingestMediaKeyPackageSignedFact(
                staleBytes,
                env.localModuleId,
                env.establishmentKeyVersion,
            ) is Profile01MediaKeyPackageIngressResult.Rejected,
        )
        assertEquals(0, env.stagedContinuation.stagedCount(env.conferenceIdHex))

        val branchBytes =
            Profile01P1DProductionIngressHarness.resignTamperedPackage(
                env.hostSigner,
                epoch3.packageBytes,
            ) { pkg ->
                pkg.copy(
                    membershipVersion = 1L,
                    mediaKeyEpoch = 2L,
                    membershipFactDigest = ByteArray(32) { 0xAB.toByte() },
                )
            }
        assertEquals(
            Profile01MediaKeyPackageContextClass.BRANCH,
            env.validator.classifyMediaKeyPackageMembershipContext(decodePackage(branchBytes)),
        )
        assertTrue(
            env.ingress.ingestMediaKeyPackageSignedFact(
                branchBytes,
                env.localModuleId,
                env.establishmentKeyVersion,
            ) is Profile01MediaKeyPackageIngressResult.Rejected,
        )
        assertEquals(0, env.stagedContinuation.stagedCount(env.conferenceIdHex))

        assertTrue(
            env.ingress.ingestMediaKeyPackageSignedFact(
                epoch3.packageBytes,
                "M03",
                env.establishmentKeyVersion,
            ) is Profile01MediaKeyPackageIngressResult.Rejected,
        )
    }

    @Test
    fun idempotence_duplicateStagedReceiveAndWireReplay() {
        val env = newEnvironment(seedHeadMediaKeyEpoch = 2L, seedHeadMembershipVersion = 1L)
        val epoch3 = env.buildEpoch3Artifacts(includeFourthMember = false)
        repeat(2) {
            assertTrue(
                env.ingress.ingestMediaKeyPackageSignedFact(
                    epoch3.packageBytes,
                    env.localModuleId,
                    env.establishmentKeyVersion,
                ) is Profile01MediaKeyPackageIngressResult.Staged,
            )
        }
        assertEquals(1, env.stagedContinuation.stagedCount(env.conferenceIdHex))
        assertEquals(
            WireIngressOutcome.INCOMPLETE_PENDING,
            env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch3.packageBytes)),
        )
        assertEquals(1, env.stagedContinuation.stagedCount(env.conferenceIdHex))
    }

    @Test
    fun fieldOrdering_epoch2ActiveKeyEpoch3StagedThenMembershipDrain() {
        val env = newEnvironment(seedHeadMediaKeyEpoch = 2L, seedHeadMembershipVersion = 1L, publishSessionAtHead = true)
        assertEquals(2L, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)

        val epoch3 = env.buildEpoch3Artifacts(includeFourthMember = false)
        assertEquals(
            WireIngressOutcome.INCOMPLETE_PENDING,
            env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch3.packageBytes)),
        )
        assertEquals(1, env.stagedContinuation.stagedCount(env.conferenceIdHex))
        assertEquals(2L, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)
        assertNull(env.supplementRegistry.lookup(env.conferenceIdHex, epoch3.mediaKeyEpoch))
        assertEquals(0, supplementReadyCount.get())

        applyMembershipAndDrain(env, epoch3.signedMembershipBytes)
        assertEquals(0, env.stagedContinuation.stagedCount(env.conferenceIdHex))
        assertTrue(env.supplementRegistry.lookup(env.conferenceIdHex, epoch3.mediaKeyEpoch) != null)
        assertEquals(1, supplementReadyCount.get())
    }

    @Test
    fun ig2_deskBaseline_afterE3AExistingChainReachesSupplementAtHead() {
        val env = newEnvironment(seedHeadMediaKeyEpoch = 2L, seedHeadMembershipVersion = 1L, publishSessionAtHead = true)
        val epoch3 = env.buildEpoch3Artifacts(includeFourthMember = false)
        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch3.packageBytes))
        applyMembershipAndDrain(env, epoch3.signedMembershipBytes)

        val head = env.ingress.currentMembershipGeneration(env.conferenceIdHex)!!
        assertEquals(3L, head.mediaKeyEpoch)
        assertTrue(env.supplementRegistry.lookup(env.conferenceIdHex, 3L) != null)
        assertEquals(1, supplementReadyCount.get())
    }

    private fun decodeMembership(bytes: ByteArray): Profile01WireMembershipFact =
        when (val decoded = Profile01WireCborDecoder.decodeMembership(bytes)) {
            is Profile01WireCborDecoder.DecodeResult.Ready -> decoded.value
            is Profile01WireCborDecoder.DecodeResult.Rejected ->
                error("membership decode failed: ${decoded.reason}")
        }

    private fun applyMembershipToRegistry(
        env: TestEnvironment,
        signedMembershipBytes: ByteArray,
    ) {
        val wire = decodeMembership(signedMembershipBytes)
        when (val applied = env.validator.membershipRegistry().applyMembership(wire)) {
            is Profile01MembershipApplyResult.Accepted,
            is Profile01MembershipApplyResult.Idempotent,
            -> Unit
            else -> error("membership apply failed: $applied")
        }
    }

    private fun applyMembershipAndDrain(
        env: TestEnvironment,
        signedMembershipBytes: ByteArray,
    ) {
        applyMembershipToRegistry(env, signedMembershipBytes)
        val generation =
            env.ingress.currentMembershipGeneration(env.conferenceIdHex)
                ?: error("membership head missing")
        env.wireIngress.drainStagedPackagesAtMembershipHeadForTest(sessionId, generation)
    }

    private fun decodePackage(bytes: ByteArray): Profile01WireMediaKeyPackage =
        (
            Profile01WireCborDecoder.decodeMediaKeyPackage(bytes)
                as Profile01WireCborDecoder.DecodeResult.Ready
        ).value

    private fun newEnvironment(
        seedHeadMediaKeyEpoch: Long,
        seedHeadMembershipVersion: Long,
        publishSessionAtHead: Boolean = false,
    ): TestEnvironment {
        val p1d = Profile01P1DProductionIngressHarness.create()
        val baseWire = decodePackage(p1d.builtPackage.signedFactBytes)
        val stagedContinuation = MeetingProfile01StagedMediaKeyPackageContinuation()
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val controlRegistry = ConferenceSessionMediaControlFactRegistry()
        val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(controlRegistry)
        val validator = p1d.validator
        validator.membershipRegistry().clear()
        seedMembershipHead(
            validator = validator,
            baseWire = baseWire,
            membershipVersion = seedHeadMembershipVersion,
            mediaKeyEpoch = seedHeadMediaKeyEpoch,
        )
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge = publisherBridge,
                mediaKeyDecrypt = p1d.ingress.mediaKeyDecryptSeam(),
                supplementRegistry = supplementRegistry,
                stagedPackageContinuation = stagedContinuation,
                onLog = {},
            ).also {
                it.onMediaSupplementReady = { _, _ -> supplementReadyCount.incrementAndGet() }
            }
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, p1d.conferenceIdHex)
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            MeetingProfile01AwareConferenceSessionMediaFactPort(controlRegistry, sessionIndex)
        val wireIngress =
            MeetingProfile01FactWireIngress(
                ingress = ingress,
                supplementRegistry = supplementRegistry,
                sessionIndex = sessionIndex,
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                incompleteApplyContinuation = MeetingProfile01IncompleteApplyContinuation(),
                stagedPackageContinuation = stagedContinuation,
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { p1d.localModuleId },
                localEstablishmentKeyVersion = { p1d.establishmentKeyVersion },
            )
        if (publishSessionAtHead) {
            supplementRegistry.put(
                com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial(
                    conferenceId = p1d.conferenceIdHex,
                    conferenceEpoch = baseWire.conferenceEpoch,
                    membershipVersion = seedHeadMembershipVersion,
                    mediaKeyEpoch = seedHeadMediaKeyEpoch,
                    masterKey = ByteArray(16) { 0x11 },
                    masterSalt = ByteArray(12) { 0x22 },
                    keyContextHint64 = ByteArray(8) { 0x33 },
                    membershipKeyContextDigest = ByteArray(32),
                ),
            )
            val declaration =
                AuthoritativeConferenceMediaSessionDeclaration(
                    conferenceId = p1d.conferenceIdHex,
                    channelId = channelId,
                    conferenceEpoch = baseWire.conferenceEpoch,
                    membershipVersion = seedHeadMembershipVersion,
                    mediaKeyEpoch = seedHeadMediaKeyEpoch,
                    endpoint = MediaGroupEndpointBinding("239.0.0.1", 5004),
                    networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
                    masterKey = ByteArray(16) { 0x11 },
                    masterSalt = ByteArray(12) { 0x22 },
                    keyContextHint64 = ByteArray(8) { 0x33 },
                    factGeneration = baseWire.conferenceEpoch * 1_000L + seedHeadMembershipVersion,
                    disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
                )
            validator.bootstrapSessionDeclaration(declaration)
            publisherBridge.publishSessionDeclaration(declaration)
        }
        return TestEnvironment(
            sessionId = sessionId,
            conferenceIdHex = p1d.conferenceIdHex,
            localModuleId = p1d.localModuleId,
            establishmentKeyVersion = p1d.establishmentKeyVersion,
            hostSigner = p1d.signer,
            validator = validator,
            ingress = ingress,
            wireIngress = wireIngress,
            stagedContinuation = stagedContinuation,
            supplementRegistry = supplementRegistry,
            controlRegistry = controlRegistry,
            baseWire = baseWire,
        )
    }

    private fun seedMembershipHead(
        validator: Profile01ConferenceMediaFactValidator,
        baseWire: Profile01WireMediaKeyPackage,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
    ) {
        val digest =
            if (membershipVersion == 0L && mediaKeyEpoch == 1L) {
                baseWire.membershipFactDigest.copyOf()
            } else {
                ByteArray(32) { (it + membershipVersion.toInt() + mediaKeyEpoch.toInt()).toByte() }
            }
        val creationSeed =
            Profile01WireSessionFact(
                signedFactBytes = byteArrayOf(),
                factDigest = digest.copyOf(),
                conferenceId = baseWire.conferenceId.toHexLower(),
                channelId = channelId,
                conferenceEpoch = baseWire.conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                membershipView =
                    listOf(
                        Profile01WireMembershipMember("M01", ByteArray(16) { 1 }),
                        Profile01WireMembershipMember(baseWire.recipientModuleId, ByteArray(16) { 2 }),
                    ),
                mediaKeyCommitment = baseWire.mediaKeyCommitment.copyOf(),
                endpoint = MediaGroupEndpointBinding("239.0.0.1", 5004),
                masterKey = ByteArray(32),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
        validator.membershipRegistry().seedFromCreation(creationSeed)
    }

    private class TestEnvironment(
        private val sessionId: String,
        val conferenceIdHex: String,
        val localModuleId: String,
        val establishmentKeyVersion: Long,
        val hostSigner: com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner,
        val validator: Profile01ConferenceMediaFactValidator,
        val ingress: Profile01ConferenceMediaFactIngress,
        val wireIngress: MeetingProfile01FactWireIngress,
        val stagedContinuation: MeetingProfile01StagedMediaKeyPackageContinuation,
        val supplementRegistry: Profile01SessionMediaSupplementRegistry,
        val controlRegistry: ConferenceSessionMediaControlFactRegistry,
        private val baseWire: Profile01WireMediaKeyPackage,
    ) {
        private val membershipPublisher = MeetingProfile01MembershipOriginPublisher(hostSigner)

        data class Epoch3Artifacts(
            val signedMembershipBytes: ByteArray,
            val packageBytes: ByteArray,
            val mediaKeyEpoch: Long,
        )

        fun buildEpoch3Artifacts(includeFourthMember: Boolean): Epoch3Artifacts {
            val current = ingress.currentMembershipGeneration(conferenceIdHex)!!
            val targetMembershipVersion = current.membershipVersion + 1L
            val targetMediaKeyEpoch = current.mediaKeyEpoch + 1L
            val membershipView =
                buildList {
                    addAll(current.members)
                    if (includeFourthMember && current.members.none { it.moduleId == "M04" }) {
                        add(Profile01WireMembershipMember("M04", ByteArray(16) { 0x44 }))
                    }
                }
            val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()
            val secret = Profile01Q5CryptoVectorFixtures.hex(corpus.inputs.conferenceMediaSecretHex)
            val contextDigest = Profile01Q5CryptoVectorFixtures.hex(corpus.expected.membershipKeyContextDigestHex)
            val commitment =
                com.talkback.core.conference.session.profile01.wire.Profile01PackageCrypto
                    .computeMediaKeyCommitment(secret, contextDigest)
            val membershipSnapshot =
                Profile01MembershipAuthoritySnapshot(
                    conferenceId = baseWire.conferenceId.copyOf(),
                    conferenceEpoch = baseWire.conferenceEpoch,
                    ownerModuleId = baseWire.ownerModuleId,
                    membershipVersion = targetMembershipVersion,
                    previousMembershipDigest = current.generationFactDigest.copyOf(),
                    members = membershipView,
                    mediaKeyEpoch = targetMediaKeyEpoch,
                    mediaKeyCommitment = commitment.copyOf(),
                    signerKeyVersion = hostSigner.signerKeyVersion,
                )
            val signedMembershipBytes = membershipPublisher.publishMembership(membershipSnapshot)
            val membershipFactDigest =
                Profile01FactDigest.computeFactDigest(
                    Profile01SignedFactEnvelope.parse(signedMembershipBytes)!!.fullCanonicalBytes,
                )
            val packageBuilt =
                Profile01MediaKeyPackageBuilder(hostSigner).build(
                    Profile01MediaKeyPackageBuildRequest(
                        material =
                            Profile01ConferenceKeyMaterial(
                                conferenceId = baseWire.conferenceId.copyOf(),
                                conferenceEpoch = baseWire.conferenceEpoch,
                                ownerModuleId = baseWire.ownerModuleId,
                                membershipVersion = targetMembershipVersion,
                                mediaKeyEpoch = targetMediaKeyEpoch,
                                conferenceMediaSecret = secret,
                                membershipKeyContextDigest = contextDigest,
                                mediaKeyCommitment = commitment,
                            ),
                        creationFactDigest = membershipFactDigest.copyOf(),
                        recipient =
                            Profile01PackageRecipientBinding(
                                recipientModuleId = localModuleId,
                                establishmentKeyVersion = establishmentKeyVersion,
                                establishmentPublicKeySpki =
                                    Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki(),
                            ),
                    ),
                ) as Profile01MediaKeyPackageBuildResult.Ready
            return Epoch3Artifacts(
                signedMembershipBytes = signedMembershipBytes,
                packageBytes = packageBuilt.signedFactBytes,
                mediaKeyEpoch = targetMediaKeyEpoch,
            )
        }

        fun signedFactEnvelope(bytes: ByteArray): SignalEnvelope =
            SignalEnvelope(
                type = SignalType.CONFERENCE_SIGNED_FACT,
                from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
                to = null,
                sessionId = sessionId,
                timestampMs = 1L,
                payload = Base64.getEncoder().encodeToString(bytes),
                nonce = "e3a-wire-nonce-${bytes.contentHashCode()}",
                signature = "e3a-wire-signature",
            )
    }
}
