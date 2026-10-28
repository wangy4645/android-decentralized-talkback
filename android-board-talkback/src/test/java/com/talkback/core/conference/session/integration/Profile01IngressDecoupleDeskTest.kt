package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.ConferenceMulticastRtpSrtpTransport
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01IngressDecoupleDeskTest {
    private val sessionId = "ingress-decouple-1"
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        Profile01IngressDecoupleObservation.resetForTest()
        Profile01IngressDecoupleObservation.enabled = true
        val fact = SessionMediaWiringHarness.sessionFact(sessionId)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
        Profile01IngressDecoupleObservation.resetForTest()
    }

    @Test
    fun receiveContinuesWhileAdmitBlocked() {
        val pollCount = AtomicInteger(0)
        val admitEntered = CountDownLatch(1)
        val releaseAdmit = CountDownLatch(1)
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
                admitProtectedDatagram = { _, _, _, _, _ ->
                    admitEntered.countDown()
                    releaseAdmit.await(2, TimeUnit.SECONDS)
                    WireIngressResult.Rejected(
                        com.talkback.core.conference.wire.WireOwningSeam.Q3,
                        "TEST_BLOCK",
                        "blocked",
                    )
                },
                ingressQueueCapacity = 8,
                admitWorkerCount = 1,
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        receiveSeam.onShadowSessionStarted(sessionId)
        assertTrue(admitEntered.await(1, TimeUnit.SECONDS))
        Thread.sleep(150)
        val pollsWhileBlocked = pollCount.get()
        releaseAdmit.countDown()
        receiveSeam.onShadowSessionStopping(sessionId)
        assertTrue("pollsWhileBlocked=$pollsWhileBlocked", pollsWhileBlocked >= 5)
    }

    @Test
    fun boundedQueueOverflowIsCounted() {
        val lines = mutableListOf<String>()
        Profile01IngressDecoupleObservation.testLogSink = { lines += it }
        val releaseAdmit = CountDownLatch(1)
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ ->
                    ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw(
                        payload = byteArrayOf(9),
                        length = 1,
                        rxWallMs = System.currentTimeMillis(),
                    )
                },
                admitProtectedDatagram = { _, _, _, _, _ ->
                    releaseAdmit.await(3, TimeUnit.SECONDS)
                    WireIngressResult.Rejected(
                        com.talkback.core.conference.wire.WireOwningSeam.Q3,
                        "TEST_HOLD",
                        "hold",
                    )
                },
                ingressQueueCapacity = 1,
                admitWorkerCount = 1,
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        receiveSeam.onShadowSessionStarted(sessionId)
        assertTrue(receiveSeam.isArmed(sessionId))
        var drops = 0L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            drops = receiveSeam.overflowDrops(sessionId)
            if (drops >= 1L) break
            Thread.sleep(20)
        }
        releaseAdmit.countDown()
        receiveSeam.onShadowSessionStopping(sessionId)
        assertTrue("overflowDrops=$drops", drops >= 1L)
        assertTrue(lines.any { it.contains("INGRESS_DECOUPLE") && it.contains("overflow=") })
    }

    @Test
    fun affinityKeepsSameSsrcOnOneShard() {
        val ssrcA = rtpStub(ssrc = 0x11111111)
        val ssrcB = rtpStub(ssrc = 0x22222222)
        val shardA =
            Profile01ShadowMulticastReceiveSeam.affinityShard(ssrcA, null, 4)
        val shardA2 =
            Profile01ShadowMulticastReceiveSeam.affinityShard(ssrcA, null, 4)
        val shardB =
            Profile01ShadowMulticastReceiveSeam.affinityShard(ssrcB, null, 4)
        assertEquals(shardA, shardA2)
        // Different SSRCs may collide; just assert API is stable. Prefer non-equal when possible.
        if (shardA == shardB) {
            // hash collision OK for N=4; still same-SSRC stable
            assertEquals(shardA, shardA2)
        } else {
            assertNotEquals(shardA, shardB)
        }
    }

    @Test
    fun parallelAdmitDifferentShardsDoNotBlockEachOther() {
        val releaseSlow = CountDownLatch(1)
        val slowEntered = CountDownLatch(1)
        val fastAdmitted = AtomicLong(0)
        val ssrcSlow = rtpStub(ssrc = 1)
        val ssrcFast = rtpStub(ssrc = 2)
        // Find two SSRCs that map to different shards.
        var slowPayload = ssrcSlow
        var fastPayload = ssrcFast
        var found = false
        for (a in 1..64) {
            for (b in 1..64) {
                if (a == b) continue
                val pa = rtpStub(a)
                val pb = rtpStub(b)
                if (Profile01ShadowMulticastReceiveSeam.affinityShard(pa, null, 4) !=
                    Profile01ShadowMulticastReceiveSeam.affinityShard(pb, null, 4)
                ) {
                    slowPayload = pa
                    fastPayload = pb
                    found = true
                    break
                }
            }
            if (found) break
        }
        assertTrue(found)

        val toggle = AtomicInteger(0)
        val receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                pollOnce = { _, _, _ ->
                    val payload = if (toggle.getAndIncrement() % 2 == 0) slowPayload else fastPayload
                    ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw(
                        payload = payload,
                        length = payload.size,
                        rxWallMs = System.currentTimeMillis(),
                    )
                },
                admitProtectedDatagram = { _, _, datagram, _, _ ->
                    val shard =
                        Profile01ShadowMulticastReceiveSeam.affinityShard(datagram, null, 4)
                    val slowShard =
                        Profile01ShadowMulticastReceiveSeam.affinityShard(slowPayload, null, 4)
                    if (shard == slowShard) {
                        slowEntered.countDown()
                        releaseSlow.await(2, TimeUnit.SECONDS)
                    } else {
                        fastAdmitted.incrementAndGet()
                    }
                    WireIngressResult.Rejected(
                        com.talkback.core.conference.wire.WireOwningSeam.Q3,
                        "TEST_PAR",
                        "par",
                    )
                },
                ingressQueueCapacity = 64,
                admitWorkerCount = 4,
                executorFactory = { Executors.newSingleThreadExecutor() },
            )
        receiveSeam.onShadowSessionStarted(sessionId)
        assertEquals(4, receiveSeam.workerCount(sessionId))
        assertTrue(slowEntered.await(1, TimeUnit.SECONDS))
        Thread.sleep(150)
        val fastWhileSlowBlocked = fastAdmitted.get()
        releaseSlow.countDown()
        receiveSeam.onShadowSessionStopping(sessionId)
        assertTrue(
            "fastWhileSlowBlocked=$fastWhileSlowBlocked",
            fastWhileSlowBlocked >= 1L,
        )
    }

    private fun rtpStub(ssrc: Int): ByteArray {
        val b = ByteArray(12)
        b[0] = 0x80.toByte()
        b[8] = ((ssrc ushr 24) and 0xff).toByte()
        b[9] = ((ssrc ushr 16) and 0xff).toByte()
        b[10] = ((ssrc ushr 8) and 0xff).toByte()
        b[11] = (ssrc and 0xff).toByte()
        return b
    }
}
