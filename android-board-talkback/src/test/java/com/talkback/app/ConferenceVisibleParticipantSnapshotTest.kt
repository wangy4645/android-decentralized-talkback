package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.SessionType
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression: the Conference UI participant projection must be populated in the
 * session snapshot. The projector [com.talkback.core.session.ConferenceParticipantProjector]
 * is correct on its own, but [TalkbackCoordinator.toSessionSnapshot] must actually run it so
 * that [TalkbackSessionSnapshot.visibleParticipants] / [visibleParticipantCount] are non-empty.
 *
 * Symptom guarded against: every device shows only itself and "0 participants" because the
 * snapshot leaves the projection fields at their defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceVisibleParticipantSnapshotTest {
    private val m01 = ModuleId("M01")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50071, m03 to 50073)
        nodeM01 = TestTalkbackNode(context, m01, 50071, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50073, hub, peers)
        nodeM01.start()
        nodeM03.start()
        Thread.sleep(300L)
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM03.stop()
    }

    @Test
    fun conferenceSnapshot_populatesVisibleParticipantsAfterIceConnected() {
        val channelId = "VISIBLE-CH"
        val sessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m03, EndpointId("E01"))),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(
            nodeM03.waitForLog(timeoutMs = 8_000L) {
                it.contains("Conference invite accepted") || it.contains("invite accepted")
            }
        )
        nodeM01.runtime.simulateRemoteIceState("M03", "COMPLETED")
        nodeM03.runtime.simulateRemoteIceState("M01", "COMPLETED")
        Thread.sleep(300L)

        val hostSnap = nodeM01.runtime.sessionSnapshots().firstOrNull { it.sessionId == sessionId }
        assertNotNull(hostSnap)
        assertEquals(SessionType.CONFERENCE, hostSnap!!.type)

        // Host must see itself + M03 = 2 visible participants.
        assertTrue(
            "expected host visibleParticipants to include local, got ${hostSnap.visibleParticipants}",
            hostSnap.visibleParticipants.any { it.isLocal }
        )
        assertEquals(2, hostSnap.visibleParticipantCount)

        // Participant side must also see itself + host.
        val partSnap = nodeM03.runtime.sessionSnapshots().firstOrNull { it.type == SessionType.CONFERENCE }
        assertNotNull(partSnap)
        assertEquals(2, partSnap!!.visibleParticipantCount)
    }
}
