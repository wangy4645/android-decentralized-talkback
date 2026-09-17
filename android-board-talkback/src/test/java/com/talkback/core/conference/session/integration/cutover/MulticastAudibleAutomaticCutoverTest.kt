package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
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
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MulticastAudibleAutomaticCutoverTest {
    private lateinit var anchor: FakeAnchorAudiblePort

    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var playoutSeam: Profile01ShadowPlayoutClockSeam
    private lateinit var receiveSeam: Profile01ShadowMulticastReceiveSeam
    private var shadowEnabledSnapshot: Boolean = true
    private var priorPlayoutSeam: Profile01ShadowPlayoutClockSeam? = null
    private var priorReceiveSeam: Profile01ShadowMulticastReceiveSeam? = null

    @Before
    fun setUp() {
        shadowEnabledSnapshot = MeetingProductMediaShadow.enabled
        MeetingProductMediaShadow.enabled = true
        priorPlayoutSeam = ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam
        priorReceiveSeam = ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam
        val context = RuntimeEnvironment.getApplication()
        wiring = ConferenceSessionMediaWiring.forShadow(context)
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
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam = playoutSeam
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = receiveSeam
        anchor = FakeAnchorAudiblePort(anchorActive = true)
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
        MeetingProductMediaShadow.enabled = shadowEnabledSnapshot
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam = priorPlayoutSeam
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = priorReceiveSeam
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun maybeAttempt_staysAnchorWhenMulticastAudibleDisabled() {
        ReplacementCutoverRc1.multicastAudibleEnabled = false
        MulticastAudibleAutomaticCutover.maybeAttempt(SESSION, LOCAL)
        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, ReplacementCutoverRc1.currentState())
    }

    @Test
    fun maybeAttempt_defersWhenNoRemoteReceivePath() {
        armLocalSoloHostPath()

        MulticastAudibleAutomaticCutover.maybeAttempt(SESSION, LOCAL)

        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, ReplacementCutoverRc1.currentState())
        assertTrue(anchor.isAnchorAudibleActive(SESSION))
        assertFalse(wiring.audiblePlayoutSeam(SESSION)?.isProductionAudioTrackActive() == true)
    }

    @Test
    fun maybeAttempt_executesRc1HandoffWhenRemoteReceivePathReady() {
        armCutoverEligiblePath()

        MulticastAudibleAutomaticCutover.maybeAttempt(SESSION, LOCAL)

        assertEquals(AudibleOwnershipState.MULTICAST_ACTIVE, ReplacementCutoverRc1.currentState())
        assertFalse(anchor.isAnchorAudibleActive(SESSION))
        assertTrue(wiring.audiblePlayoutSeam(SESSION)?.isProductionAudioTrackActive() == true)
    }

    private fun armLocalSoloHostPath() {
        playoutSeam.onShadowSessionStarted(SESSION)
        receiveSeam.onShadowSessionStarted(SESSION)
        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(LOCAL)))
    }

    private fun armCutoverEligiblePath() {
        armLocalSoloHostPath()
        assertTrue(wiring.installMember(SESSION, SessionMediaWiringHarness.memberBinding(REMOTE)))
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

    companion object {
        private const val SESSION = "session-auto-cutover"
        private const val LOCAL = "M01"
        private const val REMOTE = "M02"
    }
}
