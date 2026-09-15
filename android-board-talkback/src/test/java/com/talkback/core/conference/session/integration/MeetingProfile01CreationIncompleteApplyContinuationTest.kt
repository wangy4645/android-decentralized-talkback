package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.integration.MeetingProfile01AwareConferenceSessionMediaFactPort
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01P1DProductionIngressHarness
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
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
 * PR-P1E-2 exit tests E1–E7 — CREATION incomplete-apply continuation.
 */
class MeetingProfile01CreationIncompleteApplyContinuationTest {
    private val sessionId = Profile01P1EWireHarness.SESSION_ID
    private val channelId = Profile01DirectedWireFixtures.CHANNEL_ID

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
    fun e1_creationMissingSupplement_incompletePending_noRegistryPublish() {
        val fixture = Profile01P1EWireHarness.createDirected()
        val outcome =
            fixture.wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            )
        assertEquals(WireIngressOutcome.INCOMPLETE_PENDING, outcome)
        assertNull(fixture.registry.session(Profile01DirectedWireFixtures.CONFERENCE_ID))
        assertEquals(1, fixture.incompleteContinuation.pendingCount(Profile01DirectedWireFixtures.CONFERENCE_ID))
    }

    @Test
    fun e2_matchingSupplementPut_incompleteDrain_creationApplied() {
        val fixture = Profile01P1EWireHarness.createDirected()
        val creationWire = Profile01DirectedWireFixtures.decodeSessionWireFact(placeholderSupplement())
        assertEquals(
            WireIngressOutcome.INCOMPLETE_PENDING,
            fixture.wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            ),
        )
        val p1d = Profile01P1DProductionIngressHarness.create()
        val material =
            (
                p1d.ingress.ingestMediaKeyPackageSignedFact(
                    p1d.builtPackage.signedFactBytes,
                    p1d.localModuleId,
                    p1d.establishmentKeyVersion,
                ) as com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult.Ready
            ).material
        fixture.supplementRegistry.put(
            material.copy(
                conferenceId = Profile01DirectedWireFixtures.CONFERENCE_ID,
                mediaKeyEpoch = creationWire.mediaKeyEpoch,
            ),
        )
        fixture.wireIngress.drainIncompleteAfterSupplementReadyForTest(
            Profile01DirectedWireFixtures.CONFERENCE_ID,
            creationWire.mediaKeyEpoch,
        )
        assertNotNull(fixture.registry.session(Profile01DirectedWireFixtures.CONFERENCE_ID))
        assertEquals(0, fixture.incompleteContinuation.pendingCount(Profile01DirectedWireFixtures.CONFERENCE_ID))
    }

    @Test
    fun e3_supplementExistsFirst_creationDirectApplied_noPending() {
        val fixture = Profile01P1EWireHarness.createDirected()
        val creationWire = Profile01DirectedWireFixtures.decodeSessionWireFact(placeholderSupplement())
        val p1d = Profile01P1DProductionIngressHarness.create()
        val material =
            (
                p1d.ingress.ingestMediaKeyPackageSignedFact(
                    p1d.builtPackage.signedFactBytes,
                    p1d.localModuleId,
                    p1d.establishmentKeyVersion,
                ) as com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult.Ready
            ).material
        fixture.supplementRegistry.put(
            material.copy(
                conferenceId = Profile01DirectedWireFixtures.CONFERENCE_ID,
                mediaKeyEpoch = creationWire.mediaKeyEpoch,
            ),
        )
        val outcome =
            fixture.wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            )
        assertEquals(WireIngressOutcome.APPLIED, outcome)
        assertEquals(0, fixture.incompleteContinuation.pendingCount(Profile01DirectedWireFixtures.CONFERENCE_ID))
        assertNotNull(fixture.registry.session(Profile01DirectedWireFixtures.CONFERENCE_ID))
    }

    @Test
    fun e4_wrongConferenceOrEpoch_doesNotDrainUnrelatedPending() {
        val continuation = MeetingProfile01IncompleteApplyContinuation()
        continuation.retainPending(
            pending(
                conferenceIdHex = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                mediaKeyEpoch = 9L,
            ),
        )
        val detached =
            continuation.detachForSupplementReady(
                conferenceIdHex = Profile01DirectedWireFixtures.CONFERENCE_ID,
                mediaKeyEpoch = 2L,
            )
        assertTrue(detached.isEmpty())
        assertEquals(1, continuation.pendingCount("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

    @Test
    fun e5_sessionUnregistered_pendingDiscarded() {
        val fixture = Profile01P1EWireHarness.createDirected()
        fixture.wireIngress.onConferenceSignedFact(
            signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
        )
        assertEquals(1, fixture.incompleteContinuation.pendingCount(Profile01DirectedWireFixtures.CONFERENCE_ID))
        fixture.wireIngress.onSessionUnregistered(sessionId)
        assertEquals(0, fixture.incompleteContinuation.pendingCount(Profile01DirectedWireFixtures.CONFERENCE_ID))
    }

    @Test
    fun e6_duplicateCreationIncomplete_atMostOnePendingRecord() {
        val fixture = Profile01P1EWireHarness.createDirected()
        val first =
            fixture.wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            )
        val second =
            fixture.wireIngress.onConferenceSignedFact(
                signedFactEnvelope(Profile01DirectedWireFixtures.creationSignedFactBytes),
            )
        assertEquals(WireIngressOutcome.INCOMPLETE_PENDING, first)
        assertEquals(WireIngressOutcome.INCOMPLETE_PENDING, second)
        assertEquals(1, fixture.incompleteContinuation.pendingCount(Profile01DirectedWireFixtures.CONFERENCE_ID))
    }

    @Test
    fun e7_otherIncompleteReason_doesNotEnterContinuation() {
        val fixture = Profile01P1EWireHarness.createDirected()
        val outcome =
            fixture.wireIngress.onConferenceSignedFact(
                signedFactEnvelope(byteArrayOf(0x01, 0x02, 0x03)),
            )
        assertEquals(WireIngressOutcome.REJECTED_DECODE, outcome)
        assertEquals(0, fixture.incompleteContinuation.pendingCount(Profile01DirectedWireFixtures.CONFERENCE_ID))
    }

    private fun placeholderSupplement(): Profile01SessionMediaSupplement =
        Profile01SessionMediaSupplement(
            channelId = channelId,
            masterKey = ByteArray(32),
            masterSalt = ByteArray(12),
            keyContextHint64 = ByteArray(8),
        )

    private fun signedFactEnvelope(bytes: ByteArray): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = Base64.getEncoder().encodeToString(bytes),
            nonce = "p1e-nonce",
            signature = "p1e-signature",
        )

    private fun pending(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
        signedFactBytes: ByteArray = Profile01DirectedWireFixtures.creationSignedFactBytes,
    ): MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply {
        val routing =
            Profile01WireCborDecoder.readRoutingIdentity(signedFactBytes)
                ?: error("routing required")
        return MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply(
            sessionId = sessionId,
            conferenceIdHex = conferenceIdHex,
            mediaKeyEpoch = mediaKeyEpoch,
            factDigestHex = routing.factDigestHex,
            signedFactBytes = signedFactBytes.copyOf(),
            networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            channelId = channelId,
            retainedAtMs = System.currentTimeMillis(),
        )
    }
}

internal object Profile01P1EWireHarness {
    const val SESSION_ID = "p1e-directed-session"

    data class Fixture(
        val registry: ConferenceSessionMediaControlFactRegistry,
        val supplementRegistry: Profile01SessionMediaSupplementRegistry,
        val incompleteContinuation: MeetingProfile01IncompleteApplyContinuation,
        val wireIngress: MeetingProfile01FactWireIngress,
    )

    fun createDirected(
        incompleteContinuation: MeetingProfile01IncompleteApplyContinuation =
            MeetingProfile01IncompleteApplyContinuation(),
    ): Fixture {
        val registry = ConferenceSessionMediaControlFactRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        val validator =
            Profile01ConferenceMediaFactValidator(Profile01DirectedWireFixtures.goldenVectorTrustBoundary())
        val ingress = Profile01ConferenceMediaFactIngress(validator, bridge, supplementRegistry = supplementRegistry)
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
        sessionIndex.registerSession(SESSION_ID, Profile01DirectedWireFixtures.CHANNEL_ID, rosterEpoch = 0L)
        return Fixture(registry, supplementRegistry, incompleteContinuation, wireIngress)
    }
}
