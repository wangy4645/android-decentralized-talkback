package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TalkbackCoordinatorP14cStayBehindHeadSourceTest {
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
        val peers = TestTalkbackNode.allPeers(
            m01 to 50001,
            m02 to 50002,
            m03 to 50003,
        )
        nodeM01 = TestTalkbackNode(context, m01, 50001, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50002, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50003, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun p14c_absentNotConsumedWhenRemotePeerBindingMissing() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
            ),
            "CONF-P14C-ABSENT",
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        connectConferenceHostIce(nodeM01, nodeM02, nodeM03)

        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.runtime.leaveConference(m03SessionId)
        Thread.sleep(1_500L)

        val m02SessionId = nodeM02.runtime.activeSessionIds().single()
        assertTrue(nodeM02.runtime.testProfile01HeadSourceAbsentPeers(m02SessionId).contains("M03"))

        nodeM02.runtime.testMaybePublishHeadSourceToRejoinedPeer(
            m02SessionId,
            "M03",
            "peer_roster_reentry_membership_head",
        )
        assertTrue(
            "absent must survive failed binding gate",
            nodeM02.runtime.testProfile01HeadSourceAbsentPeers(m02SessionId).contains("M03"),
        )
        assertFalse(
            nodeM02.hasLog {
                it.contains("PROFILE01_PEER_REJOIN_HEAD_SOURCE") && it.contains("peer=M03")
            },
        )
    }

    @Test
    fun p14c_firstJoinMembershipHeadDoesNotTriggerHeadSourceRepublish() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
            ),
            "CONF-P14C-FIRST",
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        connectConferenceHostIce(nodeM01, nodeM02, nodeM03)

        val m02SessionId = nodeM02.runtime.activeSessionIds().single()
        assertTrue(nodeM02.runtime.testProfile01HeadSourceAbsentPeers(m02SessionId).isEmpty())

        nodeM02.runtime.testOnMeetingProfile01LocalShadowWireConsumptionReady(
            m02SessionId,
            "r4b_peer_membership_wire_applied",
        )
        assertFalse(
            nodeM02.hasLog {
                it.contains("PROFILE01_PEER_REJOIN_HEAD_SOURCE") &&
                    (it.contains("peer=M01") || it.contains("peer=M03"))
            },
        )
    }

    @Test
    fun p14c_stayBehindM02RepublishesHeadSourceAfterM03RejoinViaMembershipHead() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
            ),
            "CONF-P14C-REJOIN",
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        connectConferenceHostIce(nodeM01, nodeM02, nodeM03)

        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.runtime.leaveConference(m03SessionId)
        Thread.sleep(2_000L)

        val m02LogMark = synchronized(nodeM02.logs) { nodeM02.logs.size }

        val rejoinSessionId = nodeM03.runtime.requireConferenceCall(
            nodeM03.localEndpoint,
            emptyList(),
            "CONF-P14C-REJOIN",
        )
        nodeM03.runtime.sendConferenceInvites(
            rejoinSessionId,
            listOf(EndpointAddress(m01, EndpointId("E01"))),
        )
        assertTrue(nodeM01.waitForLog { it.contains("Host counter-invited M03 sent=1") })

        val deadline = System.currentTimeMillis() + 20_000L
        var m03Rejoined = false
        while (System.currentTimeMillis() < deadline) {
            if (nodeM03.runtime.activeSessionIds() == listOf(sessionId)) {
                m03Rejoined = true
                break
            }
            Thread.sleep(50L)
        }
        assertTrue("M03 should rejoin host session", m03Rejoined)

        val m02RepublishDeadline = System.currentTimeMillis() + 25_000L
        var m02EmittedToM03 = false
        while (System.currentTimeMillis() < m02RepublishDeadline) {
            val newLogs = synchronized(nodeM02.logs) { nodeM02.logs.drop(m02LogMark) }
            if (
                newLogs.any {
                    it.contains("PROFILE01_ORIGIN_EMIT") &&
                        it.contains("moduleId=M02") &&
                        it.contains("target=M03") &&
                        it.contains("outcome=EMITTED")
                } ||
                    newLogs.any {
                        it.contains("PROFILE01_PEER_REJOIN_HEAD_SOURCE") &&
                            it.contains("peer=M03") &&
                            it.contains("peer_roster_reentry_membership_head")
                    } ||
                    newLogs.any {
                        it.contains("PROFILE01_SOURCE_ORIGIN") &&
                            it.contains("peer_roster_reentry_membership_head") &&
                            it.contains("target=M03")
                    }
            ) {
                m02EmittedToM03 = true
                break
            }
            Thread.sleep(100L)
        }
        if (!m02EmittedToM03) {
            synchronized(nodeM02.logs) {
                nodeM02.logs.drop(m02LogMark).forEach { println("M02: $it") }
            }
        }
        assertTrue(
            "stay-behind M02 must emit current head SOURCE to rejoined M03",
            m02EmittedToM03,
        )
    }
}

private fun TalkbackRuntime.requireConferenceCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String,
): String = requireNotNull(conferenceCall(from, remoteEndpoints, channelId)) { "conferenceCall blocked" }
