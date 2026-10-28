package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaFact
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.PipelinePlayoutResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProductionPlayoutDeadlineIsolationDeskTest {
    private val sessionId = "deadline-play-1"
    private val anchorMs = 1_700_000_000_000L
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        assertTrue(ConferenceSessionMediaBridge.startSession(sessionFact()))
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun slowDecodeDoesNotStretchWriteCadence() {
        val writeTimes = CopyOnWriteArrayList<Long>()
        val mixCalls = AtomicInteger(0)
        val prepared = MixedBlock(ShortArray(960) { 9 }, 1, 0.2)
        val playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(sessionId) },
                sessionAnchorMs = { anchorMs },
                resolveBufferedSlot = { _, _, _ ->
                    ConferenceSessionMediaWiring.BufferedMixSlot(slot = 1L, slotMediaTimeMs = 20L)
                },
                runMixPlayoutCycle = { _, _, _, _, _, _ ->
                    mixCalls.incrementAndGet()
                    Thread.sleep(45)
                    PipelinePlayoutResult(
                        mixCycle =
                            MixCycleResult(
                                topKIdentities = setOf("M02"),
                                decodeInvocationIdentities = setOf("M02"),
                                mixParticipantIdentities = setOf("M02"),
                                mixedBlock = prepared,
                            ),
                        playoutObserved = false,
                        decodeDurationUs = 45_000,
                        mixDurationUs = 200,
                        playoutWriteDurationUs = 0,
                        playoutBudgetUsedUs = 45_200,
                        audioTrackUnderrunCount = 0,
                    )
                },
                writeMixedBlock = { _, _, _, _ ->
                    writeTimes.add(System.nanoTime())
                    true
                },
            )
        playoutSeam.onShadowSessionStarted(sessionId)
        Thread.sleep(240)
        playoutSeam.onShadowSessionStopping(sessionId)
        assertTrue("deadline writes must keep 20ms cadence, writes=${writeTimes.size}", writeTimes.size >= 8)
        assertTrue("mix must stay off the write thread, mixes=${mixCalls.get()}", mixCalls.get() < writeTimes.size)
        val intervalsMs =
            writeTimes.zipWithNext { a, b -> (b - a) / 1_000_000L }
        val over40 = intervalsMs.count { it >= 40L }
        assertTrue("write gaps>=40ms should be rare, gaps=$over40 intervals=$intervalsMs", over40 <= 2)
    }

    private fun sessionFact(): ConferenceSessionMediaFact {
        val base = SessionMediaWiringHarness.sessionFact(sessionId)
        return base.copy(startedAtMs = anchorMs)
    }
}
