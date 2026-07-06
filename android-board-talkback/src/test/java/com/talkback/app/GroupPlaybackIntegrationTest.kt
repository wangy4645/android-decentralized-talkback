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
class GroupPlaybackIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50101, m02 to 50102, m03 to 50103)
        nodeM01 = TestTalkbackNode(context, m01, 50101, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50102, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50103, hub, peers)
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
    fun groupPlayback_remoteFloorHolder_enablesListenerPlayback() {
        val channelId = "PLAYBACK-FLOOR"
        setupGroup(channelId)
        connectAnchorIce(channelId)
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(m03SessionId)
        assertTrue(waitForPlayback(nodeM02, enabled = true))
        assertFalse(waitForPlayback(nodeM03, enabled = true))
    }

    @Test
    fun groupPlayback_idleSilence_noFloorPlaybackDisabled() {
        val channelId = "PLAYBACK-IDLE"
        setupGroup(channelId)
        connectAnchorIce(channelId)
        Thread.sleep(300L)
        assertTrue(playbackDisabled(nodeM02))
        assertTrue(playbackDisabled(nodeM03))
    }

    @Test
    fun groupPlayback_iceFailedThenRecovered_restoresPlayback() {
        val channelId = "PLAYBACK-ICE"
        setupGroup(channelId)
        connectAnchorIce(channelId)
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(m03SessionId)
        assertTrue(waitForPlayback(nodeM02, enabled = true))

        nodeM02.runtime.testRefreshIceReachability(ANCHOR_ID, "FAILED")
        assertTrue(waitForPlayback(nodeM02, enabled = false))

        nodeM02.runtime.testRefreshIceReachability(ANCHOR_ID, "CONNECTED")
        assertTrue(waitForPlayback(nodeM02, enabled = true))
    }

    @Test
    fun groupPlayback_pttRelease_silencesListener() {
        val channelId = "PLAYBACK-RELEASE"
        setupGroup(channelId)
        connectAnchorIce(channelId)
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(m03SessionId)
        assertTrue(waitForPlayback(nodeM02, enabled = true))

        nodeM03.releasePtt(m03SessionId)
        assertTrue(waitForPlayback(nodeM02, enabled = false))
    }

    @Test
    fun groupPlayback_periodicSelfHeal_correctsDrift() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50111, m02 to 50112, m03 to 50113)
        val healHub = InMemorySignalingHub()
        val fastM01 = TestTalkbackNode(context, m01, 50111, healHub, peers, cleanupIntervalMs = 300L)
        val fastM02 = TestTalkbackNode(context, m02, 50112, healHub, peers, cleanupIntervalMs = 300L)
        val fastM03 = TestTalkbackNode(context, m03, 50113, healHub, peers, cleanupIntervalMs = 300L)
        fastM01.start()
        fastM02.start()
        fastM03.start()
        try {
            val channelId = "PLAYBACK-HEAL"
            startGroupOn(fastM01, fastM02, fastM03, channelId)
            listOf(fastM01, fastM02, fastM03).forEach {
                it.runtime.testForceGroupAnchorTopology(channelId)
            }
            fastM02.runtime.simulateRemoteIceState(ANCHOR_ID, "CONNECTED")
            fastM03.runtime.simulateRemoteIceState(ANCHOR_ID, "CONNECTED")
            fastM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
            fastM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
            listOf(fastM01, fastM02, fastM03).forEach {
                it.runtime.testSeedAuthorityDigestForChannel(channelId)
            }
            val m03SessionId = fastM03.runtime.activeSessionIds().single()
            fastM03.pressPtt(m03SessionId)
            assertTrue(waitForPlayback(fastM02, enabled = true))

            fastM02.runtime.testForceRemotePlayback(ANCHOR_ID, enabled = false)
            assertTrue(playbackDisabled(fastM02))
            Thread.sleep(700L)
            assertEquals(true, fastM02.runtime.remotePlaybackEnabledForModule(ANCHOR_ID))
        } finally {
            fastM01.stop()
            fastM02.stop()
            fastM03.stop()
        }
    }

    private fun setupGroup(channelId: String) {
        startGroupOn(nodeM01, nodeM02, nodeM03, channelId)
        listOf(nodeM01, nodeM02, nodeM03).forEach {
            it.runtime.testForceGroupAnchorTopology(channelId)
        }
    }

    private fun startGroupOn(
        host: TestTalkbackNode,
        peerA: TestTalkbackNode,
        peerB: TestTalkbackNode,
        channelId: String
    ) {
        val sessionId = requireNotNull(
            host.runtime.groupCall(
                host.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
        ) { "groupCall blocked" }
        assertTrue(peerA.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(peerB.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(1_000L)
        assertEquals(sessionId, host.runtime.activeSessionIds().single())
    }

    private fun connectAnchorIce(channelId: String) {
        connectGroupAnchorIce(nodeM01, nodeM02, nodeM03, channelId, ANCHOR_ID)
    }

    private fun waitForPlayback(node: TestTalkbackNode, enabled: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val actual = node.runtime.remotePlaybackEnabledForModule(ANCHOR_ID)
            if (enabled) {
                if (actual == true) return true
            } else if (actual != true) {
                return true
            }
            Thread.sleep(50)
        }
        val actual = node.runtime.remotePlaybackEnabledForModule(ANCHOR_ID)
        return if (enabled) actual == true else actual != true
    }

    private fun playbackDisabled(node: TestTalkbackNode): Boolean =
        node.runtime.remotePlaybackEnabledForModule(ANCHOR_ID) != true

    companion object {
        private const val ANCHOR_ID = "M01"
    }
}
