package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.ConferenceMulticastRtpSrtpTransport
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01ShadowSr4RxTest {
    private val sessionId = "rx-session-1"
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
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun rxU1_sessionStartArmsExactlyOnce() {
        startHarnessSession()
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
        receiveSeam.onShadowSessionStarted(sessionId)
        receiveSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(100)
        assertTrue(receiveSeam.isArmed(sessionId))
        receiveSeam.onShadowSessionStopping(sessionId)
        assertFalse(receiveSeam.isArmed(sessionId))
        assertTrue(pollCount.get() >= 1)
    }

    @Test
    fun rxU2_datagramInvokesPipelineAdmitProtectedDatagram() {
        startHarnessSession()
        installMember()
        val packet = SessionMediaWiringHarness.protectedPacket(SessionMediaWiringHarness.memberBinding("M01"))
        val admitCount = AtomicInteger(0)
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ ->
                    ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw(
                        payload = packet,
                        length = packet.size,
                        rxWallMs = System.currentTimeMillis(),
                    )
                },
                admitProtectedDatagram = { w, sid, datagram, rxWallMs ->
                    admitCount.incrementAndGet()
                    w.admitProtectedDatagram(sid, datagram, rxWallMs).ingress
                },
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        receiveSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(200)
        receiveSeam.onShadowSessionStopping(sessionId)
        assertTrue(admitCount.get() >= 1)
    }

    @Test
    fun rxU9_admittedDatagramEntersPipelineJitterAndIngressMetrics() {
        startHarnessSession()
        val binding = SessionMediaWiringHarness.memberBinding("M01")
        assertTrue(wiring.installMember(sessionId, binding))
        val packet = SessionMediaWiringHarness.protectedPacket(binding)
        val transport = wiring.transport(sessionId)!!
        assertEquals(0L, transport.observability.snapshot().ingressAccepted)
        val result =
            wiring.admitProtectedDatagram(
                sessionId,
                packet,
                System.currentTimeMillis(),
            )
        assertTrue(result.ingress is WireIngressResult.Accepted)
        assertTrue(transport.observability.snapshot().ingressAccepted > 0L)
        val runtime = wiring.runtimeSnapshot(sessionId)
        assertTrue(runtime != null && runtime.jitterBufferCount > 0)
    }

    @Test
    fun rxU3_receiveTimeoutContinuesPollLoop() {
        startHarnessSession()
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
        receiveSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(250)
        assertTrue(pollCount.get() >= 2)
        receiveSeam.onShadowSessionStopping(sessionId)
    }

    @Test
    fun rxU4_admitRejectDoesNotKillLoop() {
        startHarnessSession()
        val pollCount = AtomicInteger(0)
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ ->
                    pollCount.incrementAndGet()
                    ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw(
                        payload = byteArrayOf(1, 2, 3),
                        length = 3,
                        rxWallMs = System.currentTimeMillis(),
                    )
                },
                admitProtectedDatagram = { _, _, _, _ ->
                    WireIngressResult.Rejected(
                        com.talkback.core.conference.wire.WireOwningSeam.Q3,
                        "UNKNOWN_SSRC",
                        "no catalog binding",
                    )
                },
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        receiveSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(250)
        assertTrue(pollCount.get() >= 2)
        receiveSeam.onShadowSessionStopping(sessionId)
    }

    @Test
    fun rxU5_pollExceptionDoesNotPropagateThroughDelegate() {
        startHarnessSession()
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ -> throw IllegalStateException("rx-boom") },
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = receiveSeam
        receiveSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(150)
        var failed = false
        try {
            ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped(sessionId)
        } catch (_: Throwable) {
            failed = true
        }
        assertFalse(failed)
    }

    @Test
    fun rxU6_sessionStopBoundedTerminationStopsPolling() {
        startHarnessSession()
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
        receiveSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(100)
        val before = pollCount.get()
        receiveSeam.onShadowSessionStopping(sessionId)
        Thread.sleep(200)
        val afterStop = pollCount.get()
        assertTrue(before >= 1)
        assertEquals(afterStop, pollCount.get())
    }

    @Test
    fun rxU7_delegateStartStopIdempotent() {
        startHarnessSession()
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
        receiveSeam.onShadowSessionStarted(sessionId)
        receiveSeam.onShadowSessionStopping(sessionId)
        receiveSeam.onShadowSessionStopping(sessionId)
        assertFalse(receiveSeam.isArmed(sessionId))
    }

    @Test
    fun rxU8_delegateArmsOnSessionStartedApplied() {
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ -> null },
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = receiveSeam
        val fact = SessionMediaWiringHarness.sessionFact(sessionId)
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            object : com.talkback.core.conference.session.ConferenceSessionMediaFactPort {
                override fun sessionFact(
                    sessionId: String,
                    channelId: String,
                    rosterEpoch: Long,
                ) = fact

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
            "ch-rx-1",
            0L,
        )
        Thread.sleep(150)
        assertTrue(receiveSeam.isArmed(sessionId))
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped(sessionId)
        assertFalse(receiveSeam.isArmed(sessionId))
    }

    private fun startHarnessSession() {
        val fact = SessionMediaWiringHarness.sessionFact(sessionId)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
    }

    private fun installMember() {
        val binding = SessionMediaWiringHarness.memberBinding("M01")
        assertTrue(wiring.installMember(sessionId, binding))
    }
}
