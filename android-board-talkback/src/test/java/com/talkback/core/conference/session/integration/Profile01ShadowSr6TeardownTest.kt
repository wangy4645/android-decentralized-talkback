package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.ConferenceMulticastRtpSrtpTransport
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** PA-SR6 — peer/host teardown ordering: ingress fence → RX disarm → runtime drain. */
class Profile01ShadowSr6TeardownTest {
    private val sessionId = "sr6-teardown-session"
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var fact: com.talkback.core.conference.session.ConferenceSessionMediaFact
    private lateinit var binding: com.talkback.core.conference.session.MemberBindingFact

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        fact = SessionMediaWiringHarness.sessionFact(sessionId)
        binding = SessionMediaWiringHarness.memberBinding("M01")
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        assertTrue(wiring.installMember(sessionId, binding))
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = null
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun sr6U1_beginTeardownBlocksIngressBeforeDrain() {
        val packet = SessionMediaWiringHarness.protectedPacket(binding)
        SessionMediaWiringHarness.assertAccepted(wiring.admitDatagram(sessionId, packet))
        assertTrue(ConferenceSessionMediaBridge.beginSessionTeardown(sessionId))
        val snap = wiring.runtimeSnapshot(sessionId)!!
        assertTrue(snap.ingressBlocked)
        val reject = wiring.admitProtectedDatagram(sessionId, packet, System.currentTimeMillis())
        assertTrue(reject.ingress is WireIngressResult.Rejected)
        assertTrue(wiring.stopSession(sessionId, fact.generation))
        SessionMediaWiringHarness.assertStopped(wiring, sessionId)
    }

    @Test
    fun sr6U2_lateProtectedDatagramAfterTeardownStartIsRejected() {
        val packet = SessionMediaWiringHarness.protectedPacket(binding)
        SessionMediaWiringHarness.assertAccepted(
            wiring.admitProtectedDatagram(sessionId, packet, System.currentTimeMillis()).ingress,
        )
        assertTrue(ConferenceSessionMediaBridge.beginSessionTeardown(sessionId))
        val late = wiring.admitProtectedDatagram(sessionId, packet, System.currentTimeMillis())
        assertTrue(late.ingress is WireIngressResult.Rejected)
        assertTrue(wiring.stopSession(sessionId, fact.generation))
    }

    @Test
    fun sr6U3_rxDisarmCompletesBeforeStopSessionAssertion() {
        val pollCount = AtomicInteger(0)
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ ->
                    pollCount.incrementAndGet()
                    null
                },
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = receiveSeam
        receiveSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(100)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped(sessionId)
        assertFalse(receiveSeam.isArmed(sessionId))
        assertFalse(wiring.hasSession(sessionId))
        val afterStop = pollCount.get()
        Thread.sleep(200)
        assertEquals(afterStop, pollCount.get())
    }

    @Test
    fun sr6U4_decoderAndJitterReachZeroAfterPeerStop() {
        val packet = SessionMediaWiringHarness.protectedPacket(binding)
        val admit =
            wiring.admitProtectedDatagram(sessionId, packet, System.currentTimeMillis())
        assertTrue(admit.ingress is WireIngressResult.Accepted)
        val before = wiring.runtimeSnapshot(sessionId)!!
        assertTrue(before.jitterBufferCount > 0 || before.liveDecoders >= 0)
        wiring.withSessionPipelineLock(sessionId) {
            wiring.runMixPlayoutCycle(sessionId, System.currentTimeMillis(), 20480L, 1_000L)
        }
        val mid = wiring.runtimeSnapshot(sessionId)!!
        assertTrue(mid.liveDecoders > 0)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped(sessionId)
        SessionMediaWiringHarness.assertStopped(wiring, sessionId)
    }

    @Test
    fun sr6U5_repeatedStopIsIdempotent() {
        ConferenceSessionMediaBridge.beginSessionTeardown(sessionId)
        assertTrue(ConferenceSessionMediaBridge.beginSessionTeardown(sessionId))
        assertTrue(ConferenceSessionMediaBridge.stopSession(sessionId))
        assertFalse(ConferenceSessionMediaBridge.stopSession(sessionId))
    }

    @Test
    fun sr6U6_concurrentPacketDuringRemoteHangupCannotRecreateAuthority() {
        val packet = SessionMediaWiringHarness.protectedPacket(binding)
        val pollEntered = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val admitAfterFence = AtomicInteger(0)
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ ->
                    pollEntered.countDown()
                    releasePoll.await(3, TimeUnit.SECONDS)
                    ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw(
                        payload = packet,
                        length = packet.size,
                        rxWallMs = System.currentTimeMillis(),
                    )
                },
                admitProtectedDatagram = { w, sid, datagram, rxWallMs ->
                    val ingress = w.admitProtectedDatagram(sid, datagram, rxWallMs).ingress
                    if (ingress is WireIngressResult.Accepted) {
                        admitAfterFence.incrementAndGet()
                    }
                    ingress
                },
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = receiveSeam
        receiveSeam.onShadowSessionStarted(sessionId)
        assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
        ConferenceSessionMediaBridge.beginSessionTeardown(sessionId)
        releasePoll.countDown()
        Thread.sleep(300)
        receiveSeam.onShadowSessionStopping(sessionId)
        assertTrue(ConferenceSessionMediaBridge.stopSession(sessionId))
        assertEquals(0, admitAfterFence.get())
    }
}
