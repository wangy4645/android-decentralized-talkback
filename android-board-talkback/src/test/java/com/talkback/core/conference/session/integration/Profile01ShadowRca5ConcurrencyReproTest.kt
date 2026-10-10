package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaFact
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RCA5-A — concurrent shadow-rx ingress + shadow-playout tick must not raise CME once
 * [ConferenceSessionMediaWiring.admitProtectedDatagram] shares [withSessionPipelineLock].
 */
class Profile01ShadowRca5ConcurrencyReproTest {
    private val sessionId = "rca5-session"
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
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun rca5a_concurrentIngressAndPlayout_noConcurrentModification() {
        val fact = sessionFactWithAnchor()
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val binding = SessionMediaWiringHarness.memberBinding("M01")
        assertTrue(wiring.installMember(sessionId, binding))

        val cmeStacks = ConcurrentLinkedQueue<String>()
        val nextSlot = AtomicInteger(0x1001)
        repeat(24) {
            val packet = tonePacket(binding, nextSlot.getAndIncrement())
            val primed = wiring.admitProtectedDatagram(sessionId, packet, System.currentTimeMillis())
            assertTrue(primed.ingress is WireIngressResult.Accepted)
        }

        val playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                sessionAnchorMs = { wiring.sessionPlayoutAnchorMs(sessionId) },
                resolveBufferedSlot = { w, sid, tickMediaTimeMs ->
                    try {
                        w.resolvePlayoutMixSlot(sid, tickMediaTimeMs)
                    } catch (t: Throwable) {
                        recordCme(cmeStacks, t)
                        throw t
                    }
                },
                runMixPlayoutCycle = { w, sid, nowMs, slot, slotMediaTimeMs, _ ->
                    try {
                        w.runMixPlayoutCycle(sid, nowMs, slot, slotMediaTimeMs)
                    } catch (t: Throwable) {
                        recordCme(cmeStacks, t)
                        throw t
                    }
                },
            )
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(400)
        val mixCyclesBeforeStress = playoutSeam.mixCyclesExecuted(sessionId)
        assertTrue("playout must run before stress phase", mixCyclesBeforeStress >= 1)

        val ingressOk = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val ingressPool = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)

        val ingressTask = Runnable {
            startLatch.await(5, TimeUnit.SECONDS)
            while (!stop.get()) {
                val slot = nextSlot.getAndIncrement()
                val packet = tonePacket(binding, slot)
                try {
                    val result = wiring.admitProtectedDatagram(sessionId, packet, System.currentTimeMillis())
                    if (result.ingress is WireIngressResult.Accepted) {
                        ingressOk.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    recordCme(cmeStacks, t)
                }
                Thread.sleep(2)
            }
        }

        ingressPool.submit(ingressTask)
        ingressPool.submit(ingressTask)
        startLatch.countDown()

        Thread.sleep(4_000L)
        stop.set(true)
        ingressPool.shutdownNow()
        val mixCycles = playoutSeam.mixCyclesExecuted(sessionId)
        playoutSeam.onShadowSessionStopping(sessionId)
        println(
            "RCA5-A regression: ingressOk=${ingressOk.get()} mixCycles=$mixCycles " +
                "mixCyclesBeforeStress=$mixCyclesBeforeStress cmeStacks=${cmeStacks.size}",
        )
        for (stack in cmeStacks) {
            println(stack)
        }

        assertEquals("ConcurrentModificationException under serialized ingress/playout", 0, cmeStacks.size)
        assertTrue("expected sustained ingress admits", ingressOk.get() >= 20)
        assertTrue("expected additional playout mix cycles under concurrent load", mixCycles > mixCyclesBeforeStress)
    }

    private fun sessionFactWithAnchor(): ConferenceSessionMediaFact {
        val base = SessionMediaWiringHarness.sessionFact(sessionId)
        return base.copy(startedAtMs = anchorMs)
    }

    private fun tonePacket(
        binding: com.talkback.core.conference.session.MemberBindingFact,
        mediaSlot: Int,
    ): ByteArray {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = 10,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }

    private fun recordCme(
        stacks: ConcurrentLinkedQueue<String>,
        t: Throwable,
    ) {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is java.util.ConcurrentModificationException) {
                stacks.add(stackOf(t))
                return
            }
            cur = cur.cause
        }
    }

    private fun stackOf(t: Throwable): String =
        buildString {
            appendLine(t.javaClass.name + ": " + (t.message ?: ""))
            for (element in t.stackTrace) {
                appendLine("  at $element")
            }
            val cause = t.cause
            if (cause != null && cause !== t) {
                appendLine("Caused by: ${stackOf(cause)}")
            }
        }
}
