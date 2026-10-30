package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.MembershipHeadMaterializationOutcome
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorWireFixtures
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.transport.Phase1MediaHarness
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RCA4b — CREATION INCOMPLETE_PENDING at stale mediaKeyEpoch + membership head supplement READY
 * → session registry materialized without re-running membership publication.
 */
class MeetingProfile01CreationMembershipHeadMaterializationTest {
    private val sessionId = "rca4b-membership-head-session"
    private val conferenceId = Profile01GoldenVectorWireFixtures.CONFERENCE_ID
    private val channelId = Profile01GoldenVectorWireFixtures.CHANNEL_ID

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun staleCreationPending_membershipHeadSupplement_materializesSessionRegistry() {
        val fixture = createGoldenFixture()
        assertEquals(
            WireIngressOutcome.INCOMPLETE_PENDING,
            fixture.wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01GoldenVectorWireFixtures.creationSignedFactBytes),
            ),
        )
        assertNull(fixture.registry.session(conferenceId))
        assertEquals(1, fixture.incompleteContinuation.pendingCount(conferenceId))

        assertEquals(
            WireIngressOutcome.APPLIED,
            fixture.wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes),
            ),
        )
        assertNull(fixture.registry.session(conferenceId))

        putHeadSupplement(fixture)
        fixture.wireIngress.onSupplementReadyForConference(conferenceId, 2L)

        val sessionControl = fixture.registry.session(conferenceId)
        assertNotNull(sessionControl)
        assertEquals(2L, sessionControl!!.mediaKeyEpoch)
        assertEquals(1L, sessionControl.membershipVersion)
        assertEquals(0, fixture.incompleteContinuation.pendingCount(conferenceId))
        // RCA4b field gap: registry publish alone is not enough — SESSION_STARTED replay must fire.
        assertTrue(ConferenceSessionMediaBridge.hasSession(sessionId))
    }

    @Test
    fun tryMaterializeSessionAtMembershipHead_returnsAppliedWhenPrerequisitesReady() {
        val fixture = createGoldenFixture()
        fixture.wireIngress.onConferenceSignedFact(
            signedFactEnvelope(Profile01GoldenVectorWireFixtures.creationSignedFactBytes),
        )
        fixture.ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        putHeadSupplement(fixture)

        val outcome =
            fixture.ingress.tryMaterializeSessionAtMembershipHead(
                conferenceId = conferenceId,
                channelId = channelId,
                networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
                creationSignedBytesForEndpoint = Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
            )
        assertEquals(MembershipHeadMaterializationOutcome.APPLIED, outcome)
        assertNotNull(fixture.registry.session(conferenceId))
    }

    @Test
    fun detachSatisfiable_onlyDrainsEpochsWithSupplementInRegistry() {
        val continuation = MeetingProfile01IncompleteApplyContinuation()
        continuation.retainPending(
            pending(
                conferenceIdHex = conferenceId,
                mediaKeyEpoch = 1L,
                digest = "digest-epoch-1",
            ),
        )
        continuation.retainPending(
            pending(
                conferenceIdHex = conferenceId,
                mediaKeyEpoch = 2L,
                digest = "digest-epoch-2",
            ),
        )

        val detachedEpoch2Only =
            continuation.detachSatisfiable(
                conferenceIdHex = conferenceId,
                hasSupplementAtEpoch = { epoch -> epoch == 2L },
                triggerMediaKeyEpoch = 2L,
            )
        assertEquals(1, detachedEpoch2Only.size)
        assertEquals(2L, detachedEpoch2Only.single().pending.mediaKeyEpoch)
        assertEquals(1, continuation.pendingCount(conferenceId))
    }

    private fun putHeadSupplement(fixture: Fixture) {
        val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        fixture.supplementRegistry.put(
            Profile01DerivedMediaKeyMaterial(
                conferenceId = conferenceId,
                conferenceEpoch = sessionWire.conferenceEpoch,
                membershipVersion = 1L,
                mediaKeyEpoch = 2L,
                masterKey = Phase1MediaHarness.masterKey.copyOf(),
                masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
                keyContextHint64 = Phase1MediaHarness.keyContextHint64.copyOf(),
                membershipKeyContextDigest = ByteArray(32),
            ),
        )
    }

    private fun createGoldenFixture(): Fixture {
        val registry = ConferenceSessionMediaControlFactRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val incompleteContinuation = MeetingProfile01IncompleteApplyContinuation()
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        val validator =
            Profile01ConferenceMediaFactValidator(Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary())
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator,
                bridge,
                supplementRegistry = supplementRegistry,
            )
        val wireIngress =
            MeetingProfile01FactWireIngress(
                ingress = ingress,
                supplementRegistry = supplementRegistry,
                sessionIndex = sessionIndex,
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                incompleteApplyContinuation = incompleteContinuation,
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { "M02" },
                localEstablishmentKeyVersion = { 1L },
            )
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, conferenceId)
        return Fixture(registry, supplementRegistry, incompleteContinuation, ingress, wireIngress)
    }

    private data class Fixture(
        val registry: ConferenceSessionMediaControlFactRegistry,
        val supplementRegistry: Profile01SessionMediaSupplementRegistry,
        val incompleteContinuation: MeetingProfile01IncompleteApplyContinuation,
        val ingress: Profile01ConferenceMediaFactIngress,
        val wireIngress: MeetingProfile01FactWireIngress,
    )

    private fun signedFactEnvelope(bytes: ByteArray): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = Base64.getEncoder().encodeToString(bytes),
            nonce = "rca4b-nonce",
            signature = "rca4b-signature",
        )

    private fun pending(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
        digest: String,
    ): MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply =
        MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply(
            sessionId = sessionId,
            conferenceIdHex = conferenceIdHex,
            mediaKeyEpoch = mediaKeyEpoch,
            factDigestHex = digest,
            signedFactBytes = Profile01DirectedWireFixtures.creationSignedFactBytes.copyOf(),
            networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            channelId = channelId,
            retainedAtMs = System.currentTimeMillis(),
        )
}
