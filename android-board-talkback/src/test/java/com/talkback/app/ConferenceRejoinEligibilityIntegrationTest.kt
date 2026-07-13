package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.InviteState
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #82 / R29-F: JOINED participant HELLO recovery must not rejoin-invite or regress membership.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceRejoinEligibilityIntegrationTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50181, m02 to 50182, m03 to 50183)
        nodeM01 = TestTalkbackNode(context, m01, 50181, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50182, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50183, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
        Thread.sleep(300L)
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun testA_joinedHelloRecovered_doesNotRegressMembership() {
        val channelId = "CONF-R29F-A"
        val (sessionId, baselineJoined, baselinePending) = establishJoinedThreeParty(channelId)

        // Media loss alone — JOINED membership must stay; old gate used QoS as eligibility.
        nodeM02.runtime.simulateRemoteIceState("M03", "FAILED")
        val hostLogMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        nodeM02.runtime.testNotifyRemoteModuleRecovered("M03")

        val after = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals("joinedCount must not regress", baselineJoined, after.joinedParticipantCount)
        assertEquals("pendingCount must not regress", baselinePending, after.pendingInviteeCount)
        val m03View = after.memberViews.firstOrNull { it.moduleId == "M03" }
        assertEquals("participantState must stay JOINED/ACCEPTED", InviteState.ACCEPTED, m03View?.invite)
        assertTrue(after.memberKeys.any { it.startsWith("M03") })

        // Keep Test A focused on membership; side-effect log check is Test B.
        assertFalse(
            synchronized(nodeM02.logs) {
                nodeM02.logs.drop(hostLogMark).any { it.contains("Conference rejoin invite") }
            }
        )
    }

    @Test
    fun testB_joinedHelloRecovered_noRejoinInviteSideEffect() {
        val channelId = "CONF-R29F-B"
        establishJoinedThreeParty(channelId)

        nodeM02.runtime.simulateRemoteIceState("M03", "FAILED")
        val hostLogMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        nodeM02.runtime.testNotifyRemoteModuleRecovered("M03")
        Thread.sleep(500L)

        val logs = synchronized(nodeM02.logs) { nodeM02.logs.drop(hostLogMark) }
        assertTrue(
            "recovery handler should still run",
            logs.any { it.contains("Remote module recovered: M03") }
        )
        assertFalse(
            "JOINED must not take tryHostReinvite success path",
            logs.any { it.contains("Host re-invited M03") }
        )
        assertFalse(
            "no Conference rejoin invite for JOINED peer",
            logs.any { it.contains("Conference rejoin invite") && it.contains("M03") }
        )
        assertFalse(
            "no MEETING_INVITE / rejoin control-plane side effect",
            logs.any {
                (it.contains("GATE_DECISION op=MEETING_INVITE") ||
                    it.contains("Conference rejoin invite")) &&
                    it.contains("M03")
            }
        )
    }

    @Test
    fun leftMember_helloRecovered_stillRejoinEligible() {
        val channelId = "CONF-R29F-LEFT"
        val (sessionId, _, _) = establishJoinedThreeParty(channelId)
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.runtime.leaveConference(m03SessionId)
        assertTrue(nodeM02.waitForLog { it.contains("Conference peer left: M03") })
        assertFalse(
            nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
                .memberKeys.any { it.startsWith("M03") }
        )

        val hostLogMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        nodeM02.runtime.testNotifyRemoteModuleRecovered("M03")
        assertTrue(
            "LEFT member must remain host-reinvite eligible",
            nodeM02.waitForLogSince(hostLogMark, timeoutMs = 5_000L) {
                it.contains("Host re-invited M03") || it.contains("Conference rejoin invite")
            }
        )
    }

    private fun establishJoinedThreeParty(channelId: String): Triple<String, Int, Int> {
        nodeM01.runtime.setAutoAcceptConferenceInvites(true)
        nodeM03.runtime.setAutoAcceptConferenceInvites(true)
        val sessionId = requireNotNull(
            nodeM02.runtime.conferenceCall(
                nodeM02.localEndpoint,
                listOf(
                    EndpointAddress(m01, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
        )
        assertTrue(nodeM01.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        connectConferenceHostIce(nodeM02, nodeM01, nodeM03, hostModuleId = "M02")
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM03.runtime.simulateRemoteIceState("M02", "CONNECTED")
        Thread.sleep(400L)

        val baseline = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals(InviteState.ACCEPTED, baseline.memberViews.firstOrNull { it.moduleId == "M03" }?.invite)
        assertTrue(baseline.joinedParticipantCount >= 3)
        return Triple(sessionId, baseline.joinedParticipantCount, baseline.pendingInviteeCount)
    }
}
