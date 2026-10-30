package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaFactPort
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.session.integration.Profile01ShadowMulticastReceiveSeam
import com.talkback.core.conference.session.integration.Profile01ShadowPlayoutClockSeam
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A3-H — host LOCAL_TX readiness follows TX factPort, not RX catalog self entry.
 */
class Profile01A3HostTxAuthorityReadinessDeskTest {
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var playoutSeam: Profile01ShadowPlayoutClockSeam
    private lateinit var receiveSeam: Profile01ShadowMulticastReceiveSeam
    private val bindings = linkedMapOf<String, MemberBindingFact>()
    private var priorFactPort: ConferenceSessionMediaFactPort? = null

    private val factPort =
        object : ConferenceSessionMediaFactPort {
            override fun sessionFact(
                sessionId: String,
                channelId: String,
                rosterEpoch: Long,
            ) = SessionMediaWiringHarness.sessionFact(sessionId)

            override fun memberBinding(
                sessionId: String,
                moduleId: String,
            ): MemberBindingFact? = bindings[moduleId]

            override fun memberReplaceBinding(
                sessionId: String,
                moduleId: String,
            ): Pair<MemberBindingFact, MemberBindingFact>? = null

            override fun memberReplacePending(
                sessionId: String,
                moduleId: String,
            ): Boolean = false
        }

    @Before
    fun setUp() {
        priorFactPort = ConferenceSessionMediaCoordinatorDelegate.factPort
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        bindings.clear()
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
        ConferenceSessionMediaCoordinatorDelegate.factPort = priorFactPort
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun evaluate_localTxReadyFromFactPort_withoutRxSelfCatalogEntry() {
        assertTrue(ConferenceSessionMediaBridge.startSession(SessionMediaWiringHarness.sessionFact(SESSION)))
        playoutSeam.onShadowSessionStarted(SESSION)
        receiveSeam.onShadowSessionStarted(SESSION)
        bindings[LOCAL] = SessionMediaWiringHarness.memberBinding(LOCAL)
        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(REMOTE)))

        assertNull(ConferenceSessionMediaBridge.currentIncarnation(SESSION, LOCAL))

        val ready =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertTrue(ready.ready)
        assertFalse(ready.missing.contains("LOCAL_TX_BINDING_NOT_READY"))
    }

    @Test
    fun evaluate_localTxNotReadyWithoutFactPort_evenWhenRxSelfInstalled() {
        assertTrue(ConferenceSessionMediaBridge.startSession(SessionMediaWiringHarness.sessionFact(SESSION)))
        playoutSeam.onShadowSessionStarted(SESSION)
        receiveSeam.onShadowSessionStarted(SESSION)
        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(LOCAL)))
        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(REMOTE)))

        val result =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = SESSION,
                localModuleId = LOCAL,
                playoutSeam = playoutSeam,
                receiveSeam = receiveSeam,
            )
        assertFalse(result.ready)
        assertTrue(result.missing.contains("LOCAL_TX_BINDING_NOT_READY"))
    }

    companion object {
        private const val SESSION = "session-a3h-tx-readiness"
        private const val LOCAL = "M01"
        private const val REMOTE = "M02"
    }
}
