package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.Profile01WireSessionFact
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

class MeetingProfile01MediaKeyPackageHeadReorderTest {
    private val sessionId = "media-key-reorder-session"
    private val channelId = "p1d-test-channel"

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
    fun packageBeforeMatchingHead_isRetainedThenApplied() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire = decodedPackage(fixture)
        val env = wireEnv(fixture)
        seedPredecessorHead(fixture, wire)

        val first =
            env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes))
        assertEquals(WireIngressOutcome.REJECTED_VERIFY, first)
        assertNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))

        seedMatchingHead(fixture, wire)
        env.wireIngress.replayPendingMediaKeyAfterMembershipHead(sessionId)

        assertNotNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
        assertTrue(
            fixture.observabilityLogs.any {
                it.startsWith("PROFILE01_MEDIA_SUPPLEMENT ") && it.contains("state=READY")
            },
        )
    }

    @Test
    fun matchingHeadThenPackage_stillApplies() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val env = wireEnv(fixture)
        val outcome =
            env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes))
        assertEquals(WireIngressOutcome.APPLIED, outcome)
        val wire = decodedPackage(fixture)
        assertNotNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
    }

    @Test
    fun sameTupleDigestConflict_isNotRetained() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire = decodedPackage(fixture)
        val env = wireEnv(fixture)
        val conflicting =
            Profile01P1DProductionIngressHarness.resignTamperedPackage(
                fixture.signer,
                fixture.builtPackage.signedFactBytes,
            ) { decoded ->
                decoded.copy(membershipFactDigest = ByteArray(decoded.membershipFactDigest.size) { 0x5A })
            }

        val rejected = env.wireIngress.onConferenceSignedFact(signedFactEnvelope(conflicting))
        assertEquals(WireIngressOutcome.REJECTED_VERIFY, rejected)
        assertNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))

        val applied =
            env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes))
        assertEquals(WireIngressOutcome.APPLIED, applied)
        assertNotNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
    }

    @Test
    fun consecutiveHeadAdvance_doesNotTreatOldPackageAsCurrentReady() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire = decodedPackage(fixture)
        val env = wireEnv(fixture)
        assertEquals(
            WireIngressOutcome.APPLIED,
            env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes)),
        )
        assertTrue(env.wireIngress.isCurrentHeadSupplementInstalled(sessionId))
        assertNotNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))

        seedSuccessorHead(fixture, wire)
        assertNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch + 1))
        assertTrue(!env.wireIngress.isCurrentHeadSupplementInstalled(sessionId))

        assertEquals(
            WireIngressOutcome.REJECTED_VERIFY,
            env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes)),
        )
        env.wireIngress.replayPendingMediaKeyAfterMembershipHead(sessionId)
        assertTrue(!env.wireIngress.isCurrentHeadSupplementInstalled(sessionId))
        assertNotNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
        assertNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch + 1))
    }

    @Test
    fun delayedMatchingPackage_appliesAfterHeadCatchesUp() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire = decodedPackage(fixture)
        val env = wireEnv(fixture)
        seedPredecessorHead(fixture, wire)
        assertEquals(
            WireIngressOutcome.REJECTED_VERIFY,
            env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes)),
        )
        assertTrue(!env.wireIngress.isCurrentHeadSupplementInstalled(sessionId))
        seedMatchingHead(fixture, wire)
        env.wireIngress.replayPendingMediaKeyAfterMembershipHead(sessionId)
        assertTrue(env.wireIngress.isCurrentHeadSupplementInstalled(sessionId))
        assertNotNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
    }

    @Test
    fun lostSuccessorPackage_leavesCurrentHeadUnready() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire = decodedPackage(fixture)
        val env = wireEnv(fixture)
        env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes))
        seedSuccessorHead(fixture, wire)
        env.wireIngress.replayPendingMediaKeyAfterMembershipHead(sessionId)
        assertTrue(!env.wireIngress.isCurrentHeadSupplementInstalled(sessionId))
    }

    @Test
    fun stalePackageBehindHead_isRejected() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire = decodedPackage(fixture)
        val env = wireEnv(fixture)
        val stale =
            Profile01P1DProductionIngressHarness.resignTamperedPackage(
                fixture.signer,
                fixture.builtPackage.signedFactBytes,
            ) { decoded ->
                decoded.copy(
                    membershipVersion = (decoded.membershipVersion - 1).coerceAtLeast(0),
                    mediaKeyEpoch = (decoded.mediaKeyEpoch - 1).coerceAtLeast(0),
                )
            }
        val rejected = env.wireIngress.onConferenceSignedFact(signedFactEnvelope(stale))
        assertEquals(WireIngressOutcome.REJECTED_VERIFY, rejected)
        assertNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
    }

    @Test
    fun sessionUnregister_dropsRetainedPackage() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire = decodedPackage(fixture)
        val env = wireEnv(fixture)
        seedPredecessorHead(fixture, wire)
        env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes))
        env.wireIngress.onSessionUnregistered(sessionId)
        seedMatchingHead(fixture, wire)
        env.wireIngress.replayPendingMediaKeyAfterMembershipHead(sessionId)
        assertNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
    }

    @Test
    fun duplicateAheadPackage_doesNotDoubleApply() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire = decodedPackage(fixture)
        val env = wireEnv(fixture)
        seedPredecessorHead(fixture, wire)
        repeat(3) {
            assertEquals(
                WireIngressOutcome.REJECTED_VERIFY,
                env.wireIngress.onConferenceSignedFact(signedFactEnvelope(fixture.builtPackage.signedFactBytes)),
            )
        }
        seedMatchingHead(fixture, wire)
        env.wireIngress.replayPendingMediaKeyAfterMembershipHead(sessionId)
        assertNotNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, wire.mediaKeyEpoch))
        val readyCount =
            fixture.observabilityLogs.count {
                it.startsWith("PROFILE01_MEDIA_SUPPLEMENT ") && it.contains("state=READY")
            }
        assertEquals(1, readyCount)
    }

    private fun decodedPackage(fixture: Profile01P1DProductionIngressHarness.Fixture) =
        (
            Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready
        ).value

    private fun seedPredecessorHead(
        fixture: Profile01P1DProductionIngressHarness.Fixture,
        wire: com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage,
    ) {
        fixture.validator.membershipRegistry().fenceConferenceForAuthoritativeCatchUp(fixture.conferenceIdHex)
        fixture.validator.membershipRegistry().seedFromCreation(
            creationSeed(
                fixture = fixture,
                wire = wire,
                membershipVersion = (wire.membershipVersion - 1).coerceAtLeast(0),
                mediaKeyEpoch = (wire.mediaKeyEpoch - 1).coerceAtLeast(0),
                factDigest = ByteArray(32) { 0x11 },
            ),
        )
    }

    private fun seedSuccessorHead(
        fixture: Profile01P1DProductionIngressHarness.Fixture,
        wire: com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage,
    ) {
        fixture.validator.membershipRegistry().seedFromCreation(
            creationSeed(
                fixture = fixture,
                wire = wire,
                membershipVersion = wire.membershipVersion + 1,
                mediaKeyEpoch = wire.mediaKeyEpoch + 1,
                factDigest = ByteArray(32) { 0x22 },
            ),
        )
    }

    private fun seedMatchingHead(
        fixture: Profile01P1DProductionIngressHarness.Fixture,
        wire: com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage,
    ) {
        fixture.validator.membershipRegistry().seedFromCreation(
            creationSeed(
                fixture = fixture,
                wire = wire,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
                factDigest = wire.membershipFactDigest.copyOf(),
            ),
        )
    }

    private fun creationSeed(
        fixture: Profile01P1DProductionIngressHarness.Fixture,
        wire: com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
        factDigest: ByteArray,
    ) = Profile01WireSessionFact(
        signedFactBytes = byteArrayOf(),
        factDigest = factDigest,
        conferenceId = fixture.conferenceIdHex,
        channelId = channelId,
        conferenceEpoch = wire.conferenceEpoch,
        membershipVersion = membershipVersion,
        mediaKeyEpoch = mediaKeyEpoch,
        membershipView =
            listOf(
                Profile01WireMembershipMember("M01", byteArrayOf(1)),
                Profile01WireMembershipMember(fixture.localModuleId, byteArrayOf(2)),
            ),
        mediaKeyCommitment = wire.mediaKeyCommitment.copyOf(),
        endpoint = MediaGroupEndpointBinding("239.0.0.1", 5004),
        masterKey = ByteArray(32),
        masterSalt = ByteArray(12),
        keyContextHint64 = ByteArray(8),
    )

    private fun wireEnv(fixture: Profile01P1DProductionIngressHarness.Fixture): WireEnv {
        val registry = ConferenceSessionMediaControlFactRegistry()
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, fixture.conferenceIdHex)
        val wireIngress =
            MeetingProfile01FactWireIngress(
                ingress = fixture.ingress,
                supplementRegistry = fixture.supplementRegistry,
                sessionIndex = sessionIndex,
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { fixture.localModuleId },
                localEstablishmentKeyVersion = { fixture.authority.localEstablishmentKeyVersion() },
            )
        return WireEnv(wireIngress, registry)
    }

    private fun signedFactEnvelope(bytes: ByteArray): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = Base64.getEncoder().encodeToString(bytes),
            nonce = "reorder-nonce",
            signature = "reorder-signature",
        )

    private data class WireEnv(
        val wireIngress: MeetingProfile01FactWireIngress,
        val registry: ConferenceSessionMediaControlFactRegistry,
    )
}
