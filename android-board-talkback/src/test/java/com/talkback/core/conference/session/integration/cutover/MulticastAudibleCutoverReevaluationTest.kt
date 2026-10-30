package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaFactPort
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
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
class MulticastAudibleCutoverReevaluationTest {
    private lateinit var anchor: CountingAnchorAudiblePort
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var playoutSeam: Profile01ShadowPlayoutClockSeam
    private lateinit var receiveSeam: Profile01ShadowMulticastReceiveSeam
    private val bindings = linkedMapOf<String, MemberBindingFact>()
    private var shadowEnabledSnapshot: Boolean = true
    private var priorPlayoutSeam: Profile01ShadowPlayoutClockSeam? = null
    private var priorReceiveSeam: Profile01ShadowMulticastReceiveSeam? = null
    private var priorFactPort: ConferenceSessionMediaFactPort? = null
    private var priorLocalModuleIdProvider: (() -> String)? = null

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
        shadowEnabledSnapshot = MeetingProductMediaShadow.enabled
        MeetingProductMediaShadow.enabled = true
        priorPlayoutSeam = ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam
        priorReceiveSeam = ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam
        priorFactPort = ConferenceSessionMediaCoordinatorDelegate.factPort
        priorLocalModuleIdProvider = ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider
        bindings.clear()

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
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = { LOCAL }

        anchor = CountingAnchorAudiblePort(anchorActive = true)
        ReplacementCutoverRc1.multicastAudibleEnabled = true
        ReplacementCutoverRc1.install(
            anchorPort = anchor,
            wiringProvider = { wiring },
        )
        MulticastAudibleAutomaticCutover.onSessionStopped(SESSION)
    }

    @After
    fun tearDown() {
        MeetingProductMediaShadow.enabled = shadowEnabledSnapshot
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam = priorPlayoutSeam
        ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam = priorReceiveSeam
        ConferenceSessionMediaCoordinatorDelegate.factPort = priorFactPort
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = priorLocalModuleIdProvider
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun a_localReadyFirst_defersUntilRemoteMulticastSourceReady() {
        startSession()
        memberMediaReady(LOCAL)

        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, ReplacementCutoverRc1.currentState())
        assertTrue(anchor.isAnchorAudibleActive(SESSION))
        assertFalse(wiring.audiblePlayoutSeam(SESSION)?.isProductionAudioTrackActive() == true)
    }

    @Test
    fun b_remoteReadyAfterLocal_reevaluatesAndCutsOver() {
        startSession()
        memberMediaReady(LOCAL)
        memberMediaReady(REMOTE_M02)

        assertEquals(AudibleOwnershipState.MULTICAST_ACTIVE, ReplacementCutoverRc1.currentState())
        assertFalse(anchor.isAnchorAudibleActive(SESSION))
        assertTrue(wiring.audiblePlayoutSeam(SESSION)?.isProductionAudioTrackActive() == true)
        assertEquals(1, anchor.releaseCount)
    }

    @Test
    fun c_remoteReadyBeforeLocal_stillCutsOverWhenLocalArrives() {
        startSession()
        memberMediaReady(REMOTE_M02)

        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, ReplacementCutoverRc1.currentState())

        memberMediaReady(LOCAL)

        assertEquals(AudibleOwnershipState.MULTICAST_ACTIVE, ReplacementCutoverRc1.currentState())
        assertFalse(anchor.isAnchorAudibleActive(SESSION))
        assertTrue(wiring.audiblePlayoutSeam(SESSION)?.isProductionAudioTrackActive() == true)
    }

    @Test
    fun d_additionalRemoteReadyEvents_remainMulticastActiveWithoutOwnershipBounce() {
        startSession()
        memberMediaReady(LOCAL)
        memberMediaReady(REMOTE_M02)

        assertEquals(AudibleOwnershipState.MULTICAST_ACTIVE, ReplacementCutoverRc1.currentState())
        val releaseAfterCutover = anchor.releaseCount
        val acquireAfterCutover = anchor.acquireCount

        memberMediaReady(REMOTE_M03)
        memberMediaReady(REMOTE_M04)

        assertEquals(AudibleOwnershipState.MULTICAST_ACTIVE, ReplacementCutoverRc1.currentState())
        assertFalse(anchor.isAnchorAudibleActive(SESSION))
        assertTrue(wiring.audiblePlayoutSeam(SESSION)?.isProductionAudioTrackActive() == true)
        assertEquals(releaseAfterCutover, anchor.releaseCount)
        assertEquals(acquireAfterCutover, anchor.acquireCount)
    }

    private fun startSession() {
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(SESSION, CHANNEL, 1L)
        assertTrue(ConferenceSessionMediaBridge.hasSession(SESSION))
    }

    private fun memberMediaReady(moduleId: String) {
        bindings[moduleId] = SessionMediaWiringHarness.memberBinding(moduleId)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(SESSION, moduleId)
    }

    private inner class CountingAnchorAudiblePort(
        private var anchorActive: Boolean,
    ) : AnchorAudiblePort {
        var releaseCount: Int = 0
            private set
        var acquireCount: Int = 0
            private set

        override fun releaseAnchorOwnership(sessionId: String): Boolean {
            assertTrue(wiring.audiblePlayoutSeam(SESSION)?.isProductionAudioTrackActive() == true)
            releaseCount++
            anchorActive = false
            return true
        }

        override fun acquireAnchorOwnership(sessionId: String): Boolean {
            acquireCount++
            anchorActive = true
            return true
        }

        override fun isAnchorAudibleActive(sessionId: String): Boolean = anchorActive
    }

    companion object {
        private const val SESSION = "session-cutover-reevaluation"
        private const val CHANNEL = "CH-01"
        private const val LOCAL = "M01"
        private const val REMOTE_M02 = "M02"
        private const val REMOTE_M03 = "M03"
        private const val REMOTE_M04 = "M04"
    }
}
