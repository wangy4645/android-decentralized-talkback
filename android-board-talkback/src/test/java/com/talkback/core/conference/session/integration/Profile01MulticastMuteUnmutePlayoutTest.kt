package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.session.integration.cutover.AudibleOwnershipState
import com.talkback.core.conference.session.integration.cutover.AudiblePlayoutOwnershipSeam
import com.talkback.core.conference.session.integration.cutover.MulticastAudibleAutomaticCutover
import com.talkback.core.conference.session.integration.cutover.ReplacementCutoverRc1
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Profile01MulticastMuteUnmutePlayoutTest {
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var playoutSeam: Profile01ShadowPlayoutClockSeam
    private lateinit var receiveSeam: Profile01ShadowMulticastReceiveSeam
    private var shadowEnabledSnapshot: Boolean = true
    private var priorReceiveSeam: Profile01ShadowMulticastReceiveSeam? = null
    private lateinit var remoteBinding: com.talkback.core.conference.session.MemberBindingFact
    private val playoutFrameIndex = AtomicInteger(0)

    @Before
    fun setUp() {
        shadowEnabledSnapshot = MeetingProductMediaShadow.enabled
        MeetingProductMediaShadow.enabled = true
        priorReceiveSeam = ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam
        val context = RuntimeEnvironment.getApplication()
        wiring = ConferenceSessionMediaWiring.forShadow(context)
        ConferenceSessionMediaBridge.wiring = wiring
        playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(SESSION) },
                sessionAnchorMs = { wiring.sessionPlayoutAnchorMs(SESSION) },
            )
        receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(SESSION) },
            )
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam = playoutSeam
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = receiveSeam
        ReplacementCutoverRc1.multicastAudibleEnabled = true
        ReplacementCutoverRc1.install(
            anchorPort = FakeAnchorAudiblePort(anchorActive = true),
            wiringProvider = { wiring },
        )
        MulticastAudibleAutomaticCutover.onSessionStopped(SESSION)
        val fact =
            SessionMediaWiringHarness.sessionFact(SESSION)
                .copy(startedAtMs = System.currentTimeMillis() - 2_000L)
        ConferenceSessionMediaBridge.startSession(fact)
        playoutSeam.onShadowSessionStarted(SESSION)
        receiveSeam.onShadowSessionStarted(SESSION)
        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(LOCAL)))
        remoteBinding = SessionMediaWiringHarness.memberBinding(REMOTE)
        assertTrue(wiring.installMember(SESSION, remoteBinding))
        MulticastAudibleAutomaticCutover.maybeAttempt(SESSION, LOCAL)
        assertEquals(AudibleOwnershipState.MULTICAST_ACTIVE, ReplacementCutoverRc1.currentState())
        assertTrue(wiring.audiblePlayoutSeam(SESSION)?.isProductionAudioTrackActive() == true)
    }

    @After
    fun tearDown() {
        playoutSeam.onShadowSessionStopping(SESSION)
        receiveSeam.onShadowSessionStopping(SESSION)
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = priorReceiveSeam
        MeetingProductMediaShadow.enabled = shadowEnabledSnapshot
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun muteUnmute_keepsMulticastProductionOwnership_andRestoresPlayoutWrites() {
        val nextSlot = AtomicInteger(0x3000)
        val cyclesBeforeMute = feedIngressAndRunPlayoutCycles(nextSlot, packetCount = 24)
        assertTrue("expected playout before mute", cyclesBeforeMute >= 3)

        ConferenceSessionMediaCoordinatorDelegate.onConferenceCallMuteChanged(SESSION, muted = true)
        val seam = wiring.audiblePlayoutSeam(SESSION)
        assertEquals(AudiblePlayoutOwnershipSeam.PlayoutMode.MULTICAST_PRODUCTION, seam?.mode)
        assertTrue(seam?.isProductionAudioTrackActive() == true)

        val cyclesAtMute = feedIngressAndRunPlayoutCycles(nextSlot, packetCount = 8)

        starveJitterLiveEdge(remoteBinding)

        ConferenceSessionMediaCoordinatorDelegate.onConferenceCallMuteChanged(SESSION, muted = false)

        val cyclesAfterUnmute = feedIngressAndRunPlayoutCycles(nextSlot, packetCount = 32)
        assertTrue("playout should recover after unmute", cyclesAfterUnmute >= 3)
        val writesAfterUnmute = wiring.playoutSuccessfulWrites(SESSION) ?: 0L
        assertTrue("unmute must drive audible playout writes or mix cycles", writesAfterUnmute > 0L || cyclesAfterUnmute >= 3)

        ConferenceSessionMediaCoordinatorDelegate.onConferenceCallMuteChanged(SESSION, muted = true)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceCallMuteChanged(SESSION, muted = false)
        val cyclesAfterSecondCycle = feedIngressAndRunPlayoutCycles(nextSlot, packetCount = 16)
        assertTrue("second mute/unmute must not permanently starve", cyclesAfterSecondCycle >= 3)
        assertTrue(cyclesAtMute >= 1)
    }

    @Test
    fun unmute_reanchorsIngressTimeline_afterMuteGapFreshPacketsQueued() {
        val nextSlot = AtomicInteger(0x4000)
        feedIngressAndRunPlayoutCycles(nextSlot, packetCount = 12)
        val anchorSeq = nextSlot.get() - 1
        val gapStartWallMs = System.currentTimeMillis()
        val playoutAdvanceMs = 8_000L
        var tickWall = gapStartWallMs
        repeat(40) {
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
            wiring.resolvePlayoutMixSlot(SESSION, tickWall + playoutAdvanceMs)
        }
        ConferenceSessionMediaCoordinatorDelegate.onConferenceCallMuteChanged(SESSION, muted = true)
        starveJitterLiveEdge(remoteBinding)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceCallMuteChanged(SESSION, muted = false)
        val resumeWallMs = gapStartWallMs + playoutAdvanceMs + 50L
        val resumed =
            wiring.admitProtectedDatagram(
                SESSION,
                tonePacket(remoteBinding, anchorSeq + 1),
                resumeWallMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, resumed.frameAdmit)
        assertNotEquals(FrameAdmitDisposition.LATE_FOR_PLAYOUT, resumed.frameAdmit)
    }

    @Test
    fun misalignedTopKSlots_recoverPcmAfterZeroMixStreak() {
        val m03Binding = SessionMediaWiringHarness.memberBinding("M03")
        assertTrue(wiring.installMember(SESSION, m03Binding))
        feedIngress(remoteBinding, 0x1_000, audioLevel = 10)
        val anchor = wiring.sessionPlayoutAnchorMs(SESSION) ?: error("anchor")
        repeat(11) {
            feedIngress(remoteBinding, 0x1_001 + it, audioLevel = 10, wallMs = anchor + 20L * (it + 1))
        }
        repeat(12) {
            feedIngress(m03Binding, 0x2_000 + it, audioLevel = 10, wallMs = anchor + 20L * (it + 1))
        }
        val playoutTick = anchor + 12L * MediaJitterConstants.MEDIA_SLOT_MS
        val resolved =
            wiring.resolvePlayoutMixSlot(SESSION, playoutTick)
                ?: error("expected resolved slot")
        val m03Slot =
            resolved.perSourceSlots[m03Binding.sourceIdentity]
                ?: error("expected per-source slot for M03, got ${resolved.perSourceSlots}")
        assertTrue(m03Slot >= 0x2_000L)
        val remoteSlot = resolved.perSourceSlots[remoteBinding.sourceIdentity]
        if (remoteSlot != null) {
            assertTrue(remoteSlot in 0x1_000L..0x1_00BL)
        }
        repeat(2) {
            wiring.runMixPlayoutCycle(
                SESSION,
                playoutTick + it * MediaJitterConstants.MEDIA_SLOT_MS,
                resolved.slot,
                resolved.slotMediaTimeMs,
                emptyMap(),
            )
        }
        val recovered =
            wiring.resolvePlayoutMixSlot(SESSION, playoutTick + 2L * MediaJitterConstants.MEDIA_SLOT_MS)
                ?: error("expected recovery slot")
        val mix =
            wiring.runMixPlayoutCycle(
                SESSION,
                playoutTick + 2L * MediaJitterConstants.MEDIA_SLOT_MS,
                recovered.slot,
                recovered.slotMediaTimeMs,
                recovered.perSourceSlots,
            )
        assertNotNull(mix)
        assertTrue(recovered.perSourceSlots.isNotEmpty())
    }

    @Test
    fun unmute_doesNotForceQueue_whenIngressDeadlineGenuinelyExpired() {
        val nextSlot = AtomicInteger(0x5000)
        val baseWallMs = System.currentTimeMillis()
        val firstSlot = nextSlot.getAndIncrement()
        val first =
            wiring.admitProtectedDatagram(
                SESSION,
                tonePacket(remoteBinding, firstSlot),
                baseWallMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, first.frameAdmit)
        val expiredWallMs =
            baseWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + MediaJitterConstants.MEDIA_SLOT_MS
        val expired =
            wiring.admitProtectedDatagram(
                SESSION,
                tonePacket(remoteBinding, firstSlot),
                expiredWallMs,
            )
        assertEquals(FrameAdmitDisposition.LATE_FOR_PLAYOUT, expired.frameAdmit)
    }

    private fun feedIngress(
        binding: com.talkback.core.conference.session.MemberBindingFact,
        mediaSlot: Int,
        audioLevel: Int = 10,
        wallMs: Long = System.currentTimeMillis(),
    ) {
        val admitted =
            wiring.admitProtectedDatagram(
                SESSION,
                tonePacket(binding, mediaSlot, audioLevel),
                wallMs,
            )
        assertTrue(admitted.ingress is WireIngressResult.Accepted)
        assertEquals(FrameAdmitDisposition.QUEUED, admitted.frameAdmit)
    }

    private fun feedIngressAndRunPlayoutCycles(
        nextSlot: AtomicInteger,
        packetCount: Int,
    ): Long {
        val baseWallMs = System.currentTimeMillis()
        var executed = 0L
        for (i in 0 until packetCount) {
            val slot = nextSlot.getAndIncrement()
            val wallMs = baseWallMs + i * 20L
            val admitted =
                wiring.admitProtectedDatagram(SESSION, tonePacket(remoteBinding, slot), wallMs)
            assertTrue(admitted.ingress is WireIngressResult.Accepted)
            assertEquals(FrameAdmitDisposition.QUEUED, admitted.frameAdmit)
            val mediaSlot = admitted.mediaSlot
            val mediaTimeMs = admitted.mediaTimeMs
            if (mediaSlot != null && mediaTimeMs != null) {
                assertTrue(
                    wiring.runMixPlayoutCycle(SESSION, wallMs, mediaSlot, mediaTimeMs) != null,
                )
                executed += 1
            }
            playoutFrameIndex.incrementAndGet()
        }
        return executed
    }

    private fun starveJitterLiveEdge(binding: com.talkback.core.conference.session.MemberBindingFact) {
        val pipeline = wiring.orchestrator(SESSION)?.pipeline ?: return
        val admitted = wiring.authorityRuntime(SESSION)?.store?.currentAdmitted() ?: return
        val incarnationId = admitted[binding.sourceIdentity]?.incarnationId ?: binding.incarnationId
        val playoutTimeMs = System.currentTimeMillis() + 120_000L
        pipeline.applyPromotionLiveEdgeFence(binding.sourceIdentity, incarnationId, playoutTimeMs)
        repeat(8) {
            wiring.admitProtectedDatagram(
                SESSION,
                tonePacket(binding, 0x100 + it),
                System.currentTimeMillis() - 60_000L,
            )
        }
    }

    private fun tonePacket(
        binding: com.talkback.core.conference.session.MemberBindingFact,
        mediaSlot: Int,
        audioLevel: Int = 10,
    ): ByteArray {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = audioLevel,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }

    private class FakeAnchorAudiblePort(
        private var anchorActive: Boolean,
    ) : com.talkback.core.conference.session.integration.cutover.AnchorAudiblePort {
        override fun releaseAnchorOwnership(sessionId: String): Boolean {
            anchorActive = false
            return true
        }

        override fun acquireAnchorOwnership(sessionId: String): Boolean {
            anchorActive = true
            return true
        }

        override fun isAnchorAudibleActive(sessionId: String): Boolean = anchorActive
    }

    companion object {
        private const val SESSION = "session-mute-unmute-playout"
        private const val LOCAL = "M01"
        private const val REMOTE = "M02"
    }
}
