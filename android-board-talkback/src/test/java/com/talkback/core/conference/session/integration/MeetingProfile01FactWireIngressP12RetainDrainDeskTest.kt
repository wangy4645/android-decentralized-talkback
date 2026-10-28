package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam
import com.talkback.core.conference.session.profile01.wire.Profile01Q5EmbeddedVectors
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/** P12/P13 — retained SOURCE survives CREATION/membership drain until mediaKeyEpoch converges. */
class MeetingProfile01FactWireIngressP12RetainDrainDeskTest {
    private val sessionId = "p12-retain-drain-session"
    private val channelId = Profile01DirectedWireFixtures.CHANNEL_ID
    private val conferenceId = Profile01DirectedWireFixtures.CONFERENCE_ID
    private val registry = ConferenceSessionMediaControlFactRegistry()
    private val supplementRegistry =
        com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry()
    private val sessionIndex = MeetingProfile01ConferenceSessionIndex()
    private val preBindRetention = MeetingProfile01PreBindFactRetention()
    private val ingress =
        Profile01ConferenceMediaFactIngress(
            validator =
                Profile01ConferenceMediaFactValidator(
                    Profile01DirectedWireFixtures.goldenVectorTrustBoundary(),
                ),
            publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry),
            mediaKeyDecrypt =
                Profile01MediaKeyPackageDecryptSeam(
                    Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam(),
                ),
            supplementRegistry = supplementRegistry,
        )
    private lateinit var wireIngress: MeetingProfile01FactWireIngress

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
        wireIngress = newWireIngress()
        seedQ5Material()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun creationApplied_doesNotConsumeRetainedSource() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedRetention = MeetingProfile01PreBindFactRetention()
        val isolatedIngress = newWireIngress(isolatedIndex, isolatedRetention)

        assertEquals(
            WireIngressOutcome.DEFERRED_NO_SESSION_RETAINED,
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes),
            ),
        )
        isolatedIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        isolatedIndex.bindConferenceId(sessionId, conferenceId)

        assertEquals(
            WireIngressOutcome.APPLIED,
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            ),
        )

        val stillRetained =
            isolatedRetention.detachForDrain(
                conferenceId,
                setOf(Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION),
            )
        assertEquals(1, stillRetained.size)
    }

    @Test
    fun membershipHead_doesNotConsumeRetainedSource() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedRetention = MeetingProfile01PreBindFactRetention()
        val isolatedIngress = newWireIngress(isolatedIndex, isolatedRetention)

        assertEquals(
            WireIngressOutcome.DEFERRED_NO_SESSION_RETAINED,
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes),
            ),
        )

        isolatedIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        isolatedIndex.bindConferenceId(sessionId, conferenceId)

        assertEquals(
            WireIngressOutcome.APPLIED,
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            ),
        )
        assertEquals(
            WireIngressOutcome.APPLIED,
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.membershipSignedFactBytes),
            ),
        )

        val stillRetained =
            isolatedRetention.detachForDrain(
                conferenceId,
                setOf(Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION),
            )
        assertEquals(1, stillRetained.size)
    }

    @Test
    fun retainedSource_publishesAfterMediaKeyEpochAlignedDrain() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedRetention = MeetingProfile01PreBindFactRetention()
        val isolatedIngress = newWireIngress(isolatedIndex, isolatedRetention)
        val remoteModuleId = Profile01DirectedWireFixtures.decodeMemberSourceWireFact().moduleId

        isolatedIngress.onConferenceSignedFact(
            signedFactEnvelope(Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes),
        )
        isolatedIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        isolatedIndex.bindConferenceId(sessionId, conferenceId)

        assertEquals(
            WireIngressOutcome.APPLIED,
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            ),
        )
        assertEquals(
            WireIngressOutcome.APPLIED,
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.membershipSignedFactBytes),
            ),
        )

        val generation = ingress.currentMembershipGeneration(conferenceId)
        assertNotNull(generation)
        assertEquals(
            generation!!.mediaKeyEpoch,
            ConferenceSessionMediaBridge.currentMediaKeyEpoch(sessionId),
        )

        val drainOutcome =
            isolatedIngress.drainRetainedAfterSessionBind(
                sessionId,
                setOf(Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION),
            )
        assertEquals(1, drainOutcome.applied)
        assertNotNull(registry.member(conferenceId, remoteModuleId))
    }

    @Test
    fun staleSourceAtMembershipHead_stillRejected_notTreatedAsApplied() {
        val outcome =
            wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.staleSourceDeclarationSignedFactBytes),
            )
        assertEquals(WireIngressOutcome.REJECTED_PUBLISH, outcome)
    }

    private fun newWireIngress(
        index: MeetingProfile01ConferenceSessionIndex = sessionIndex,
        retention: MeetingProfile01PreBindFactRetention = preBindRetention,
    ): MeetingProfile01FactWireIngress =
        MeetingProfile01FactWireIngress(
            ingress = ingress,
            supplementRegistry = supplementRegistry,
            sessionIndex = index,
            preBindRetention = retention,
            networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
            localModuleId = { Profile01Q5EmbeddedVectors.RECIPIENT_MODULE_ID },
            localEstablishmentKeyVersion = { Profile01Q5EmbeddedVectors.RECIPIENT_KEY_VERSION },
        )

    private fun seedQ5Material() {
        val decrypt =
            Profile01MediaKeyPackageDecryptSeam(Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam())
        val decrypted =
            decrypt.decrypt(
                Profile01Q5EmbeddedVectors.wirePackage(),
                Profile01Q5EmbeddedVectors.RECIPIENT_MODULE_ID,
                Profile01Q5EmbeddedVectors.RECIPIENT_KEY_VERSION,
            )
        require(decrypted is Profile01MediaKeyPackageDecryptResult.Ready)
        supplementRegistry.put(
            decrypted.material.copy(
                conferenceId = conferenceId,
                conferenceEpoch = 7L,
                membershipVersion = 0L,
                mediaKeyEpoch = 1L,
            ),
        )
    }

    private fun signedFactEnvelope(bytes: ByteArray): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = Base64.getEncoder().encodeToString(bytes),
            nonce = "p12-nonce",
            signature = "p12-signature",
        )
}
