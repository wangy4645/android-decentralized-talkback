package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.presence.PresenceProjector
import com.talkback.core.session.SessionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PresenceSnapshotIntegrationTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = com.talkback.core.signaling.InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50201, m02 to 50202, m03 to 50203)
        nodeM01 = TestTalkbackNode(context, m01, 50201, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50202, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50203, hub, peers)
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
    fun grantBeforeIceReady_protocolOwnerSet_uplinkGrantFalse() {
        val channelId = "PRESENCE-ACQUIRE"
        setupGroup(channelId)
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(m03SessionId)
        assertTrue(waitForProtocolOwner(nodeM03, m03SessionId, nodeM03.localEndpoint.key))

        val sessionPresence = requireNotNull(nodeM03.runtime.sessionPresenceSnapshot(m03SessionId))
        val modulePresence = nodeM03.runtime.modulePresenceSnapshot()

        assertEquals(nodeM03.localEndpoint.key, sessionPresence.protocolFloorOwnerKey)
        assertFalse(modulePresence.localUplinkGrant)
        assertFalse(nodeM03.runtime.testIsSessionCapturing(m03SessionId))
        assertTrue(
            PresenceProjector.satisfiesInvariantF1(
                sessionPresence,
                modulePresence,
                nodeM03.localEndpoint.key
            )
        )
    }

    @Test
    fun grantWithIceReady_uplinkGrantTrue() {
        val channelId = "PRESENCE-CAPTURE"
        setupGroup(channelId)
        connectAnchorIce(channelId)
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(m03SessionId)
        assertTrue(waitForProtocolOwner(nodeM03, m03SessionId, nodeM03.localEndpoint.key))
        assertTrue(waitForUplinkGrant(nodeM03))

        val sessionPresence = requireNotNull(nodeM03.runtime.sessionPresenceSnapshot(m03SessionId))
        val modulePresence = nodeM03.runtime.modulePresenceSnapshot()

        assertEquals(nodeM03.localEndpoint.key, sessionPresence.protocolFloorOwnerKey)
        assertTrue(modulePresence.localUplinkGrant)
        assertEquals(nodeM03.localEndpoint.key, modulePresence.activeCaptureEndpointKey)
    }

    @Test
    fun conferenceSessionPresence_omitsFloorField() {
        val channelId = "PRESENCE-CONF"
        val sessionId = requireNotNull(
            nodeM01.runtime.conferenceCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                channelId
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(300L)

        val presence = nodeM01.runtime.sessionPresenceSnapshot(sessionId)
        assertNotNull(presence)
        assertEquals(SessionType.CONFERENCE, presence!!.type)
        assertNull(presence.protocolFloorOwnerKey)
    }

    private fun setupGroup(channelId: String) {
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(500L)
        assertNotNull(sessionId)
        listOf(nodeM01, nodeM02, nodeM03).forEach {
            it.runtime.testForceGroupAnchorTopology(channelId)
        }
    }

    private fun connectAnchorIce(channelId: String) {
        connectGroupAnchorIce(nodeM01, nodeM02, nodeM03, channelId, ANCHOR_ID)
    }

    private fun waitForProtocolOwner(
        node: TestTalkbackNode,
        sessionId: String,
        ownerKey: String
    ): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val snap = node.runtime.sessionPresenceSnapshot(sessionId)
            if (snap?.protocolFloorOwnerKey == ownerKey) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun waitForUplinkGrant(node: TestTalkbackNode): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (node.runtime.modulePresenceSnapshot().localUplinkGrant) return true
            Thread.sleep(50)
        }
        return false
    }

    companion object {
        private const val ANCHOR_ID = "M01"
    }
}
