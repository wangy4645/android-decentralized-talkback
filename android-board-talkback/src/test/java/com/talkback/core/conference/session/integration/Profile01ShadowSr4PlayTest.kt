package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaFact
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.AbsoluteMediaPlayoutClock
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01ShadowSr4PlayTest {
    private val sessionId = "play-session-1"
    private val anchorMs = 1_700_000_000_000L
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = null
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun playU1_armedClockInvokesRunMixPlayoutCycle() {
        startHarnessSession()
        val binding = installMember()
        admitTonePacket(binding, mediaSlot = 0x1001)
        val mixCalls = AtomicInteger(0)
        val playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                sessionAnchorMs = { anchorMs },
                runMixPlayoutCycle = { w, sid, nowMs, slot, slotMediaTimeMs, _ ->
                    mixCalls.incrementAndGet()
                    w.runMixPlayoutCycle(sid, nowMs, slot, slotMediaTimeMs)
                },
            )
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(300)
        playoutSeam.onShadowSessionStopping(sessionId)
        assertTrue(mixCalls.get() >= 1)
    }

    @Test
    fun playU2_duplicateArmIsExactlyOnceWithStableAnchor() {
        startHarnessSession()
        val anchors = mutableListOf<Long>()
        val clocksStarted = AtomicInteger(0)
        val playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                sessionAnchorMs = {
                    anchors.add(anchorMs)
                    anchorMs
                },
                clockFactory = { anchor, threadName, onTick ->
                    clocksStarted.incrementAndGet()
                    AbsoluteMediaPlayoutClock(anchorMs = anchor, threadName = threadName, onTick = onTick)
                },
            )
        playoutSeam.onShadowSessionStarted(sessionId)
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(100)
        assertEquals(1, clocksStarted.get())
        assertEquals(1, anchors.size)
        assertTrue(playoutSeam.isArmed(sessionId))
        playoutSeam.onShadowSessionStopping(sessionId)
        assertFalse(playoutSeam.isArmed(sessionId))
    }

    @Test
    fun playU3_jitterSourceAdvancesDecoderMixAndWrite() {
        startHarnessSession()
        val binding = installMember()
        admitTonePackets(binding, mediaSlots = intArrayOf(0x1001, 0x1002, 0x1003))
        val playoutSeam = defaultPlayoutSeam()
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(800)
        playoutSeam.onShadowSessionStopping(sessionId)
        val writes = wiring.playoutSuccessfulWrites(sessionId) ?: 0L
        val snap = wiring.runtimeSnapshot(sessionId)
        assertTrue(writes > 0L)
        assertTrue((snap?.liveDecoders ?: 0) > 0 || writes > 0L)
    }

    @Test
    fun playU4_emptyJitterTicksWithoutFailure() {
        startHarnessSession()
        installMember()
        val playoutSeam = defaultPlayoutSeam()
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(250)
        playoutSeam.onShadowSessionStopping(sessionId)
        assertEquals(0L, playoutSeam.mixCyclesExecuted(sessionId))
    }

    @Test
    fun playU5_tickExceptionIsolatedAndSubsequentTicksContinue() {
        startHarnessSession()
        val binding = installMember()
        admitTonePacket(binding, mediaSlot = 0x1001)
        var calls = 0
        val playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                sessionAnchorMs = { anchorMs },
                runMixPlayoutCycle = { _, _, _, _, _, _ ->
                    calls += 1
                    if (calls == 1) throw IllegalStateException("play-boom")
                    null
                },
            )
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(400)
        assertTrue(calls >= 2)
        playoutSeam.onShadowSessionStopping(sessionId)
    }

    @Test
    fun playU6_stopPreventsFurtherMixCycles() {
        startHarnessSession()
        val binding = installMember()
        admitTonePacket(binding, mediaSlot = 0x1001)
        val mixCalls = AtomicInteger(0)
        val playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                sessionAnchorMs = { anchorMs },
                runMixPlayoutCycle = { w, sid, nowMs, slot, slotMediaTimeMs, _ ->
                    mixCalls.incrementAndGet()
                    w.runMixPlayoutCycle(sid, nowMs, slot, slotMediaTimeMs)
                },
            )
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(200)
        val beforeStop = mixCalls.get()
        playoutSeam.onShadowSessionStopping(sessionId)
        Thread.sleep(250)
        assertTrue(beforeStop >= 1)
        assertEquals(beforeStop, mixCalls.get())
    }

    @Test
    fun playU7_duplicateStopIsIdempotent() {
        startHarnessSession()
        val playoutSeam = defaultPlayoutSeam()
        playoutSeam.onShadowSessionStarted(sessionId)
        playoutSeam.onShadowSessionStopping(sessionId)
        playoutSeam.onShadowSessionStopping(sessionId)
        assertFalse(playoutSeam.isArmed(sessionId))
    }

    @Test
    fun playU8_delegateArmsPlayoutOnSessionStartedAndStopsInOrder() {
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ -> null },
                executorFactory = { java.util.concurrent.Executors.newSingleThreadExecutor() },
            )
        val playoutSeam = defaultPlayoutSeam()
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = receiveSeam
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam = playoutSeam
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            object : com.talkback.core.conference.session.ConferenceSessionMediaFactPort {
                override fun sessionFact(
                    sessionId: String,
                    channelId: String,
                    rosterEpoch: Long,
                ) = sessionFactWithAnchor()

                override fun memberBinding(
                    sessionId: String,
                    moduleId: String,
                ) = null

                override fun memberReplaceBinding(
                    sessionId: String,
                    moduleId: String,
                ) = null

                override fun memberReplacePending(
                    sessionId: String,
                    moduleId: String,
                ) = false
            }
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionId,
            "ch-play-1",
            0L,
        )
        Thread.sleep(150)
        assertTrue(receiveSeam.isArmed(sessionId))
        assertTrue(playoutSeam.isArmed(sessionId))
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped(sessionId)
        assertFalse(receiveSeam.isArmed(sessionId))
        assertFalse(playoutSeam.isArmed(sessionId))
    }

    @Test
    fun playU9_tickExceptionDoesNotPropagateThroughDelegateStop() {
        val playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                sessionAnchorMs = { anchorMs },
                runMixPlayoutCycle = { _, _, _, _, _, _ -> throw IllegalStateException("play-boom") },
            )
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam = playoutSeam
        startHarnessSession()
        val binding = installMember()
        admitTonePacket(binding, mediaSlot = 0x1001)
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(150)
        var failed = false
        try {
            ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped(sessionId)
        } catch (_: Throwable) {
            failed = true
        }
        assertFalse(failed)
    }

    private fun defaultPlayoutSeam(): Profile01ShadowPlayoutClockSeam =
        Profile01ShadowPlayoutClockSeam(
            wiringProvider = { wiring },
            hasSession = { wiring.hasSession(sessionId) },
            sessionAnchorMs = { wiring.sessionPlayoutAnchorMs(sessionId) },
        )

    private fun startHarnessSession() {
        assertTrue(ConferenceSessionMediaBridge.startSession(sessionFactWithAnchor()))
    }

    private fun sessionFactWithAnchor(): ConferenceSessionMediaFact {
        val base = SessionMediaWiringHarness.sessionFact(sessionId)
        return base.copy(startedAtMs = anchorMs)
    }

    private fun installMember(): com.talkback.core.conference.session.MemberBindingFact {
        val binding = SessionMediaWiringHarness.memberBinding("M01")
        assertTrue(wiring.installMember(sessionId, binding))
        return binding
    }

    private fun admitTonePackets(
        binding: com.talkback.core.conference.session.MemberBindingFact,
        mediaSlots: IntArray,
    ) {
        for (slot in mediaSlots) {
            admitTonePacket(binding, mediaSlot = slot)
        }
    }

    private fun admitTonePacket(
        binding: com.talkback.core.conference.session.MemberBindingFact,
        mediaSlot: Int = 0x1001,
    ) {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = 10,
                frequencyHz = 440.0,
            )
        val packet = Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
        val result =
            wiring.admitProtectedDatagram(
                sessionId,
                packet,
                System.currentTimeMillis(),
            )
        assertTrue(result.ingress is WireIngressResult.Accepted)
    }
}
