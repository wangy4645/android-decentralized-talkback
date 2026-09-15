package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MulticastAudibleAutomaticCutoverTest {
    private lateinit var anchor: FakeAnchorAudiblePort
    private lateinit var multicast: FakeMulticastAudiblePort

    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        anchor = FakeAnchorAudiblePort(anchorActive = true)
        multicast = FakeMulticastAudiblePort()
        ReplacementCutoverRc1.multicastAudibleEnabled = true
        ReplacementCutoverRc1.install(
            anchorPort = anchor,
            wiringProvider = { wiring },
        )
        MulticastAudibleAutomaticCutover.onSessionStopped(SESSION)
        ConferenceSessionMediaBridge.startSession(SessionMediaWiringHarness.sessionFact(SESSION))
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun maybeAttempt_staysAnchorWhenMulticastAudibleDisabled() {
        ReplacementCutoverRc1.multicastAudibleEnabled = false
        MulticastAudibleAutomaticCutover.maybeAttempt(SESSION, LOCAL)
        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, ReplacementCutoverRc1.currentState())
    }

    private class FakeAnchorAudiblePort(
        private var anchorActive: Boolean,
    ) : AnchorAudiblePort {
        override fun releaseAnchorOwnership(sessionId: String): Boolean {
            anchorActive = false
            return true
        }

        override fun acquireAnchorOwnership(sessionId: String): Boolean {
            anchorActive = true
            return true
        }

        override fun isAnchorAudibleActive(sessionId: String): Boolean = anchorActive
    }

    private class FakeMulticastAudiblePort : MulticastAudiblePort {
        var productionActive: Boolean = false

        override fun fenceProductionPlayout(sessionId: String) {
            productionActive = false
        }

        override fun acquireProductionAudioTrack(sessionId: String): Boolean {
            productionActive = true
            return true
        }

        override fun releaseProductionAudioTrack(sessionId: String) {
            productionActive = false
        }

        override fun isProductionAudioTrackActive(sessionId: String): Boolean = productionActive
    }

    companion object {
        private const val SESSION = "session-auto-cutover"
        private const val LOCAL = "M01"
    }
}
