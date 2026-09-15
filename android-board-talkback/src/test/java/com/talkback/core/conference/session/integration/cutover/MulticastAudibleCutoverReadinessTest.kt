package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.session.integration.Profile01ShadowPlayoutClockSeam
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MulticastAudibleCutoverReadinessTest {
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var playoutSeam: Profile01ShadowPlayoutClockSeam

    @Before
    fun setUp() {
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        playoutSeam =
            Profile01ShadowPlayoutClockSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(SESSION) },
                sessionAnchorMs = { wiring.sessionPlayoutAnchorMs(SESSION) },
            )
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun evaluate_notReadyUntilSessionBindingAndPlayoutArmed() {
        val missingSession =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
            )
        assertFalse(missingSession.ready)
        assertTrue(missingSession.missing.contains("MULTICAST_SESSION_NOT_MATERIALIZED"))

        assertTrue(ConferenceSessionMediaBridge.startSession(SessionMediaWiringHarness.sessionFact(SESSION)))
        val missingPlayout =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
            )
        assertFalse(missingPlayout.ready)
        assertTrue(missingPlayout.missing.contains("AUDIBLE_PLAYOUT_PATH_NOT_READY"))

        playoutSeam.onShadowSessionStarted(SESSION)
        val missingBinding =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
            )
        assertFalse(missingBinding.ready)
        assertTrue(missingBinding.missing.contains("LOCAL_TX_BINDING_NOT_READY"))

        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(LOCAL)))
        val ready =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
            )
        assertTrue(ready.ready)
        assertTrue(ready.missing.isEmpty())
    }

    companion object {
        private const val SESSION = "session-readiness"
        private const val LOCAL = "M01"
    }
}
