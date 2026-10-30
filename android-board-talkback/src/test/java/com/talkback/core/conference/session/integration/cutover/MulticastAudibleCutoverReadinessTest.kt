package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.session.integration.Profile01ShadowMulticastReceiveSeam
import com.talkback.core.conference.session.integration.Profile01ShadowPlayoutClockSeam
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MulticastAudibleCutoverReadinessTest {
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var playoutSeam: Profile01ShadowPlayoutClockSeam
    private lateinit var receiveSeam: Profile01ShadowMulticastReceiveSeam

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
        receiveSeam =
            Profile01ShadowMulticastReceiveSeam(
                wiringProvider = { wiring },
                hasSession = { wiring.hasSession(SESSION) },
            )
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun evaluate_soloHostDefersUntilRemoteReceivePathReady() {
        assertTrue(ConferenceSessionMediaBridge.startSession(SessionMediaWiringHarness.sessionFact(SESSION)))
        playoutSeam.onShadowSessionStarted(SESSION)
        receiveSeam.onShadowSessionStarted(SESSION)
        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(LOCAL)))

        val soloHost =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertFalse(soloHost.ready)
        assertTrue(soloHost.missing.contains("REMOTE_MULTICAST_RX_SOURCE_NOT_READY"))
    }

    @Test
    fun evaluate_notReadyUntilSessionBindingPlayoutAndRemoteReceiveArmed() {
        val missingSession =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertFalse(missingSession.ready)
        assertTrue(missingSession.missing.contains("MULTICAST_SESSION_NOT_MATERIALIZED"))

        assertTrue(ConferenceSessionMediaBridge.startSession(SessionMediaWiringHarness.sessionFact(SESSION)))
        val missingPlayout =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertFalse(missingPlayout.ready)
        assertTrue(missingPlayout.missing.contains("AUDIBLE_PLAYOUT_PATH_NOT_READY"))

        playoutSeam.onShadowSessionStarted(SESSION)
        val missingReceive =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertFalse(missingReceive.ready)
        assertTrue(missingReceive.missing.contains("MULTICAST_RECEIVE_PATH_NOT_READY"))

        receiveSeam.onShadowSessionStarted(SESSION)
        val missingBinding =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertFalse(missingBinding.ready)
        assertTrue(missingBinding.missing.contains("LOCAL_TX_BINDING_NOT_READY"))

        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(LOCAL)))
        val missingRemote =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertFalse(missingRemote.ready)
        assertTrue(missingRemote.missing.contains("REMOTE_MULTICAST_RX_SOURCE_NOT_READY"))

        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(REMOTE)))
        val ready =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertTrue(ready.ready)
        assertTrue(ready.missing.isEmpty())
    }

    companion object {
        private const val SESSION = "session-readiness"
        private const val LOCAL = "M01"
        private const val REMOTE = "M02"
    }
}
