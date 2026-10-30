package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.Profile01MembershipIngressResult
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01SignedFactDecodeResult
import com.talkback.core.conference.session.profile01.Profile01ValidationResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam
import com.talkback.core.conference.session.profile01.wire.Profile01Q5EmbeddedVectors
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01ShadowMemberBindingMaterializerTest {
    private val sessionId = "g2-peer-binding-materializer"
    private val channelId = Profile01DirectedWireFixtures.CHANNEL_ID
    private val localModuleId = Profile01Q5EmbeddedVectors.RECIPIENT_MODULE_ID

    private val registry = ConferenceSessionMediaControlFactRegistry()
    private val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry)
    private val supplementRegistry = Profile01SessionMediaSupplementRegistry()
    private val sessionIndex = MeetingProfile01ConferenceSessionIndex()
    private val ingress =
        Profile01ConferenceMediaFactIngress(
            validator =
                Profile01ConferenceMediaFactValidator(
                    Profile01DirectedWireFixtures.goldenVectorTrustBoundary(),
                ),
            publisherBridge = publisherBridge,
            mediaKeyDecrypt =
                Profile01MediaKeyPackageDecryptSeam(
                    Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam(),
                ),
            supplementRegistry = supplementRegistry,
        )
    private val factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
    private lateinit var materializer: Profile01ShadowMemberBindingMaterializer
    private var retryCount = 0

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, Profile01DirectedWireFixtures.CONFERENCE_ID)
        seedSupplement()
        materializer =
            Profile01ShadowMemberBindingMaterializer(
                factPort = factPort,
                ingress = ingress,
                localModuleId = { localModuleId },
                readSignedLocalSource = { null },
                retryLocalSourceBuild = { retryCount++ },
            )
        val creationResult =
            ingress.ingestCreationSignedFact(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
                Profile01SessionMediaSupplement(
                    channelId = channelId,
                    masterKey = ByteArray(16),
                    masterSalt = ByteArray(12),
                    keyContextHint64 = ByteArray(8),
                ),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        assertEquals(Profile01SignedFactDecodeResult.Ready, creationResult.decode)
        assertTrue(creationResult.ingress?.validation is Profile01ValidationResult.ReadySession)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, creationResult.ingress?.publishOutcome)
        val membership =
            ingress.ingestMembershipSignedFact(Profile01DirectedWireFixtures.membershipSignedFactBytes)
        assertTrue(membership is Profile01MembershipIngressResult.Converged)
        val membershipVersion = Profile01DirectedWireFixtures.decodeMembershipWireFact().membershipVersion
        Profile01ShadowSessionReplay.replaySessionStarted(sessionId, channelId, membershipVersion)
        assertTrue(ConferenceSessionMediaBridge.hasSession(sessionId))
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun onCreationWireApplied_retriesLocalBuildHook() {
        materializer.onCreationWireApplied(sessionId)
        assertEquals(1, retryCount)
    }

    @Test
    fun materializeLocalIfReady_retriesLocalBuildHook() {
        materializer.materializeLocalIfReady(sessionId)
        assertEquals(1, retryCount)
    }

    @Test
    fun onSourceDeclarationWireApplied_installsRemoteBindingWithoutMediaConnectedGate() {
        val hostSigned = Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes
        val hostIngress = ingress.ingestSourceDeclarationSignedFact(hostSigned)
        assertEquals(Profile01SignedFactDecodeResult.Ready, hostIngress.decode)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, hostIngress.ingress?.publishOutcome)
        assertNull(ConferenceSessionMediaBridge.currentIncarnation(sessionId, Profile01DirectedWireFixtures.decodeMemberSourceWireFact().moduleId))

        materializer.onSourceDeclarationWireApplied(
            sessionId,
            Profile01DirectedWireFixtures.decodeMemberSourceWireFact().moduleId,
        )

        assertNotNull(
            ConferenceSessionMediaBridge.currentIncarnation(
                sessionId,
                Profile01DirectedWireFixtures.decodeMemberSourceWireFact().moduleId,
            ),
        )
    }

    private fun seedSupplement() {
        val q5Wire = Profile01Q5EmbeddedVectors.wirePackage()
        val decrypt =
            Profile01MediaKeyPackageDecryptSeam(Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam())
        val decrypted =
            decrypt.decrypt(
                q5Wire,
                Profile01Q5EmbeddedVectors.RECIPIENT_MODULE_ID,
                Profile01Q5EmbeddedVectors.RECIPIENT_KEY_VERSION,
            )
        require(decrypted is Profile01MediaKeyPackageDecryptResult.Ready)
        supplementRegistry.put(
            decrypted.material.copy(
                conferenceId = Profile01DirectedWireFixtures.CONFERENCE_ID,
                conferenceEpoch = 7L,
                membershipVersion = 0L,
                mediaKeyEpoch = 1L,
            ),
        )
    }
}
