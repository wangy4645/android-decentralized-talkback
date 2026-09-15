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
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.session.GroupMembershipSupport
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeetingProfile01FactWireIngressTest {
    private val sessionId = "meeting-session-1"
    private val channelId = Profile01DirectedWireFixtures.CHANNEL_ID
    private val registry = ConferenceSessionMediaControlFactRegistry()
    private val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
    private val supplementRegistry = com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry()
    private val sessionIndex = MeetingProfile01ConferenceSessionIndex()
    private val preBindRetention = MeetingProfile01PreBindFactRetention()
    private val validator =
        Profile01ConferenceMediaFactValidator(Profile01DirectedWireFixtures.goldenVectorTrustBoundary())
    private val ingress =
        Profile01ConferenceMediaFactIngress(
            validator = validator,
            publisherBridge = bridge,
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
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        wireIngress = newWireIngress()
        seedQ5Material()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
        MeetingProductMediaShadow.enabled = true
    }

    @Test
    fun paSrA1_creationOnly_readsSessionFactWithPublishedMembershipVersionZero() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        isolatedIndex.registerSession(
            sessionId,
            channelId,
            rosterEpoch = GroupMembershipSupport.INITIAL_ROSTER_EPOCH,
        )
        isolatedIndex.bindConferenceId(sessionId, Profile01DirectedWireFixtures.CONFERENCE_ID)
        val isolatedIngress = newWireIngress(isolatedIndex)
        val factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(registry, isolatedIndex)
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort

        assertNull(factPort.sessionFact(sessionId, channelId, 0L))

        val creationOutcome =
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            )
        assertEquals(WireIngressOutcome.APPLIED, creationOutcome)
        assertNotNull(factPort.sessionFact(sessionId, channelId, 0L))
        assertNull(factPort.sessionFact(sessionId, channelId, GroupMembershipSupport.INITIAL_ROSTER_EPOCH))

        val snap = MeetingProductMediaShadow.observability.snapshot()
        val outcomes = snap["outcomeCounts"] as Map<*, *>
        assertTrue((outcomes[ShadowOutcome.APPLIED.name] as Long) >= 1L)
    }

    @Test
    fun creationThenMembership_replaysDeferredSessionStarted() {
        val beforeSnap = MeetingProductMediaShadow.observability.snapshot()
        val beforeDeferred =
            (beforeSnap["outcomeCounts"] as Map<*, *>)[ShadowOutcome.DEFERRED_NO_FACT.name] as Long? ?: 0L
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(sessionId, channelId, 0L)
        var snap = MeetingProductMediaShadow.observability.snapshot()
        assertEquals(
            beforeDeferred + 1L,
            (snap["outcomeCounts"] as Map<*, *>)[ShadowOutcome.DEFERRED_NO_FACT.name],
        )

        val creationOutcome =
            wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            )
        assertEquals(WireIngressOutcome.APPLIED, creationOutcome)

        val membershipOutcome =
            wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.membershipSignedFactBytes),
            )
        assertEquals(WireIngressOutcome.APPLIED, membershipOutcome)

        snap = MeetingProductMediaShadow.observability.snapshot()
        val outcomes = snap["outcomeCounts"] as Map<*, *>
        assertTrue((outcomes[ShadowOutcome.APPLIED.name] as Long) >= 1L)
        assertTrue(registry.session(Profile01DirectedWireFixtures.CONFERENCE_ID) != null)
    }

    @Test
    fun t1_factBeforeSession_retainBindDrainApplied() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedRetention = MeetingProfile01PreBindFactRetention()
        val isolatedIngress = newWireIngress(isolatedIndex, isolatedRetention)
        val envelope =
            signedFactEnvelope(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
                sessionId = sessionId,
            )
        val retainOutcome = isolatedIngress.onConferenceSignedFact(envelope)
        assertEquals(WireIngressOutcome.DEFERRED_NO_SESSION_RETAINED, retainOutcome)

        isolatedIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        isolatedIndex.bindConferenceId(sessionId, Profile01DirectedWireFixtures.CONFERENCE_ID)
        val drainOutcome = isolatedIngress.drainRetainedAfterSessionBind(sessionId)
        assertEquals(1, drainOutcome.applied)
        assertEquals(0, drainOutcome.fenced)
        assertTrue(registry.session(Profile01DirectedWireFixtures.CONFERENCE_ID) != null)
    }

    @Test
    fun t2_sessionBeforeFact_directPathUnchanged() {
        val outcome =
            wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            )
        assertEquals(WireIngressOutcome.APPLIED, outcome)
        val drainOutcome = wireIngress.drainRetainedAfterSessionBind(sessionId)
        assertEquals(0, drainOutcome.applied)
    }

    @Test
    fun t3_wrongConference_bindDoesNotDrainOther() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedRetention = MeetingProfile01PreBindFactRetention()
        val isolatedIngress = newWireIngress(isolatedIndex, isolatedRetention)
        isolatedIngress.onConferenceSignedFact(
            signedFactEnvelope(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
                sessionId = sessionId,
            ),
        )
        val otherSessionId = "other-meeting-session-99"
        isolatedIndex.registerSession(otherSessionId, channelId, rosterEpoch = 0L)
        val drainOutcome = isolatedIngress.drainRetainedAfterSessionBind(otherSessionId)
        assertEquals(0, drainOutcome.applied)
        assertEquals(0, drainOutcome.fenced)
        assertTrue(isolatedRetention.detachForDrain(Profile01DirectedWireFixtures.CONFERENCE_ID).size == 1)
    }

    @Test
    fun t4_duplicateBind_retentionConsumedOnce() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedRetention = MeetingProfile01PreBindFactRetention()
        val isolatedIngress = newWireIngress(isolatedIndex, isolatedRetention)
        isolatedIngress.onConferenceSignedFact(
            signedFactEnvelope(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
                sessionId = sessionId,
            ),
        )
        isolatedIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        isolatedIndex.bindConferenceId(sessionId, Profile01DirectedWireFixtures.CONFERENCE_ID)
        val first = isolatedIngress.drainRetainedAfterSessionBind(sessionId)
        val second = isolatedIngress.drainRetainedAfterSessionBind(sessionId)
        assertEquals(1, first.applied)
        assertEquals(0, second.applied)
    }

    @Test
    fun t5_expiredBeforeBind_noAppliedObservableDiscard() {
        var nowMs = 1_000L
        val isolatedRetention = MeetingProfile01PreBindFactRetention(clock = { nowMs })
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedIngress = newWireIngress(isolatedIndex, isolatedRetention)
        isolatedIngress.onConferenceSignedFact(
            signedFactEnvelope(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
                sessionId = sessionId,
            ),
        )
        nowMs += MeetingProfile01PreBindFactRetention.MAX_RETENTION_MS + 1
        isolatedIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        isolatedIndex.bindConferenceId(sessionId, Profile01DirectedWireFixtures.CONFERENCE_ID)
        val drainOutcome = isolatedIngress.drainRetainedAfterSessionBind(sessionId)
        assertEquals(0, drainOutcome.applied)
        assertTrue(isolatedRetention.detachForDrain(Profile01DirectedWireFixtures.CONFERENCE_ID).isEmpty())
    }

    @Test
    fun b1_5_preBindSource_notRetained() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedIngress = newWireIngress(isolatedIndex, MeetingProfile01PreBindFactRetention())
        val outcome =
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(
                    Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes,
                    sessionId = "unknown-session",
                ),
            )
        assertEquals(WireIngressOutcome.PRE_BIND_NOT_RETAINED, outcome)
    }

    @Test
    fun preBindMembership_retainedUntilSessionBind() {
        val isolatedIndex = MeetingProfile01ConferenceSessionIndex()
        val isolatedRetention = MeetingProfile01PreBindFactRetention()
        val isolatedIngress = newWireIngress(isolatedIndex, isolatedRetention)
        val outcome =
            isolatedIngress.onConferenceSignedFact(
                signedFactEnvelope(
                    Profile01DirectedWireFixtures.membershipSignedFactBytes,
                    sessionId = "unknown-session",
                ),
            )
        assertEquals(WireIngressOutcome.DEFERRED_NO_SESSION_RETAINED, outcome)
    }

    @Test
    fun malformedPayload_rejectsDecode() {
        val outcome =
            wireIngress.onConferenceSignedFact(
                SignalEnvelope(
                    type = SignalType.CONFERENCE_SIGNED_FACT,
                    from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
                    to = null,
                    sessionId = sessionId,
                    timestampMs = 1L,
                    payload = "not-valid-base64!!!",
                    nonce = "n1",
                    signature = "s1",
                ),
            )
        assertEquals(WireIngressOutcome.REJECTED_DECODE, outcome)
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

    private fun signedFactEnvelope(
        bytes: ByteArray,
        sessionId: String = this.sessionId,
    ): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = Base64.getEncoder().encodeToString(bytes),
            nonce = "wire-test-nonce",
            signature = "wire-test-signature",
        )

}
