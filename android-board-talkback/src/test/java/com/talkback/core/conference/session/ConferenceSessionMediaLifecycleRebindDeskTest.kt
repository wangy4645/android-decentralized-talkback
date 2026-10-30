package com.talkback.core.conference.session

import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.transport.ConferenceMulticastRtpSrtpTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** P1 option-2 desk — lifecycle rebind keeps transport scope active. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceSessionMediaLifecycleRebindDeskTest {
    private val sessionId = "p1-opt2-rebind-session"
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forShadow(RuntimeEnvironment.getApplication())
        ConferenceSessionMediaBridge.wiring = wiring
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun lifecycleRebind_keepsScopeActive() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = 1_700_000_000_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val transport: ConferenceMulticastRtpSrtpTransport = wiring.transport(sessionId)!!
        assertTrue(transport.isScopeActive())

        val outcome =
            wiring.rebindTransportForLifecycle(sessionId, "SCREEN_OFF", 1_700_000_001_000L)
        assertTrue(
            outcome == ConferenceSessionMediaWiring.TransportLifecycleRebindOutcome.REBOUND ||
                outcome == ConferenceSessionMediaWiring.TransportLifecycleRebindOutcome.JOIN_FAILED,
        )
        assertTrue(transport.isScopeActive())
        assertTrue(transport.lastJoinAttempted())

        val again =
            wiring.rebindTransportForLifecycle(sessionId, "SCREEN_ON", 1_700_000_003_000L)
        assertEquals(
            ConferenceSessionMediaWiring.TransportLifecycleRebindOutcome.SKIPPED_NO_SESSION,
            wiring.rebindTransportForLifecycle("missing", "SCREEN_ON", 1_700_000_004_000L),
        )
        assertTrue(
            again == ConferenceSessionMediaWiring.TransportLifecycleRebindOutcome.REBOUND ||
                again == ConferenceSessionMediaWiring.TransportLifecycleRebindOutcome.JOIN_FAILED,
        )
    }
}
