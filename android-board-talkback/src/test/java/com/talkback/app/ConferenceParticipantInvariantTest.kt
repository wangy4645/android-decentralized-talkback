package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.InviteState
import com.talkback.core.session.MediaState
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private fun TalkbackRuntime.requireGroupCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String
): String = requireNotNull(groupCall(from, remoteEndpoints, channelId)) { "groupCall blocked" }

private fun TalkbackRuntime.requireConferenceCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String
): String = requireNotNull(conferenceCall(from, remoteEndpoints, channelId)) { "conferenceCall blocked" }

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceParticipantInvariantTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50061, m02 to 50062, m03 to 50063)
        nodeM01 = TestTalkbackNode(context, m01, 50061, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50062, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50063, hub, peers)
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
    fun conferenceInvite_ringTimeout_doesNotEvictConnectedPeer() {
        val peers = TestTalkbackNode.allPeers(m01 to 50071, m02 to 50072, m03 to 50073)
        val context = RuntimeEnvironment.getApplication()
        val nodeHost = TestTalkbackNode(
            context,
            m01,
            50071,
            hub,
            peers,
            sessionIdleTimeoutMs = 120_000L,
            cleanupIntervalMs = 200L,
            conferenceInviteRingTimeoutMs = 3_000L
        )
        val nodeM02Local = TestTalkbackNode(context, m02, 50072, hub, peers)
        val nodeM03Local = TestTalkbackNode(context, m03, 50073, hub, peers, autoReDialOnModuleRecovery = false)
        nodeHost.start()
        nodeM02Local.start()
        nodeM03Local.start()
        try {
            nodeM02Local.runtime.setAutoAcceptConferenceInvites(true)
            nodeM03Local.runtime.setAutoAcceptConferenceInvites(false)
            val channelId = "CONF-TTL-SPLIT"
            val sessionId = nodeHost.runtime.requireConferenceCall(
                nodeHost.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
            assertTrue(nodeM02Local.waitForLog { it.contains("invite accepted") })
            Thread.sleep(200L)
            val beforeExpire = nodeHost.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertTrue(beforeExpire.memberKeys.any { it.startsWith("M02") })

            assertTrue(
                nodeHost.waitForLog(timeoutMs = 12_000L) {
                    it.contains("Conference invite expired for M03")
                }
            )
            val afterExpire = nodeHost.runtime.sessionSnapshotForChannel(channelId)
            assertNotNull(afterExpire)
            assertTrue(
                "connected peer M02 must remain in roster after M03 ring timeout",
                afterExpire!!.memberKeys.any { it.startsWith("M02") }
            )
            assertFalse(afterExpire.memberKeys.any { it.startsWith("M03") })
            val m02View = afterExpire.memberViews.firstOrNull { it.moduleId == "M02" }
            assertTrue(
                m02View == null ||
                    m02View.media == MediaState.CONNECTED ||
                    m02View.invite == InviteState.ACCEPTED
            )
        } finally {
            nodeHost.stop()
            nodeM02Local.stop()
            nodeM03Local.stop()
        }
    }

    @Test
    fun conferenceHangup_meetingPreferredDoesNotCorruptGroupRosterOnOtherChannel() {
        val groupChannel = "CH-GROUP-ISO"
        val confChannel = "CH-CONF-ISO"
        nodeM01.runtime.requireGroupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            groupChannel
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(500L)
        val groupBefore = nodeM01.runtime.sessionSnapshotForChannel(groupChannel)
        assertNotNull(groupBefore)
        val rosterBefore = groupBefore!!.memberKeys.toSet()

        nodeM02.runtime.setMeetingPreferred(true, confChannel)
        Thread.sleep(500L)

        val groupAfter = nodeM01.runtime.sessionSnapshotForChannel(groupChannel)
        assertNotNull(groupAfter)
        assertEquals(rosterBefore, groupAfter!!.memberKeys.toSet())
    }

    @Test
    fun conferenceBusyYield_hostRosterUnchanged() {
        val channelId = "CONF-BUSY-ROSTER"
        nodeM02.runtime.setAutoAcceptConferenceInvites(true)
        val hostSessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        connectConferenceHostIce(nodeM01, nodeM02)
        val hostBefore = nodeM01.runtime.sessionSnapshots().first { it.sessionId == hostSessionId }
        val rosterBefore = hostBefore.memberKeys.toSet()

        nodeM03.runtime.setAutoAcceptConferenceInvites(false)
        nodeM01.runtime.sendConferenceInvites(
            hostSessionId,
            listOf(EndpointAddress(m03, EndpointId("E01")))
        )
        val pendingDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pendingDeadline) {
            if (nodeM03.runtime.pendingConferenceInvite(channelId) != null) break
            Thread.sleep(50L)
        }
        assertNotNull(nodeM03.runtime.pendingConferenceInvite(channelId))

        nodeM03.runtime.requireConferenceCall(nodeM03.localEndpoint, emptyList(), channelId)
        assertTrue(nodeM03.runtime.acceptPendingConferenceInvite(channelId))
        assertTrue(
            nodeM03.waitForLog {
                it.contains("Yielding local solo conference to host invite on $channelId")
            }
        )
        Thread.sleep(800L)

        val hostAfter = nodeM01.runtime.sessionSnapshots().first { it.sessionId == hostSessionId }
        assertTrue(hostAfter.memberKeys.containsAll(rosterBefore))
        assertTrue(hostAfter.memberKeys.any { it.startsWith("M02") })
    }
}
