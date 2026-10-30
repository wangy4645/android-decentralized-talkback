package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A0.5b — hangup bootstrap ingress gate defers before GROUP bootstrap side effects. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HangupBootstrapIngressGateTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private val channelId = "CH-01"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50031, m02 to 50032)
        nodeM01 = TestTalkbackNode(context, m01, 50031, hub, peers)
        nodeM01.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
    }

    @Test
    fun deferHangupBootstrap_returnsTrueWhileBarrierActive() {
        nodeM01.runtime.testActivateHangupBootstrapBarrier(channelId)

        assertTrue(nodeM01.runtime.deferHangupBootstrap(channelId, "test_origin"))
        nodeM01.runtime.reconcileGroupMeshSync(channelId)
        assertTrue(nodeM01.runtime.deferHangupBootstrap(channelId, "reconcile_group_mesh"))

        nodeM01.runtime.testClearHangupBootstrapBarrier(channelId)
        assertFalse(nodeM01.runtime.deferHangupBootstrap(channelId, "test_origin"))
    }

    @Test
    fun groupCall_blockedWhileHangupBootstrapBarrierActive() {
        nodeM01.runtime.testActivateHangupBootstrapBarrier(channelId)
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        val local = EndpointAddress(m01, EndpointId("E01"))
        val remote = EndpointAddress(ModuleId("M02"), EndpointId("E02"))

        val sessionId = nodeM01.runtime.groupCall(local, listOf(remote), channelId)

        assertNull(sessionId)
        assertTrue(
            nodeM01.waitForLogSince(logMark) {
                it.contains("Blocked GROUP mesh") && it.contains(channelId)
            }
        )
        assertFalse(nodeM01.hasLog { it.contains("Group call initiated") })
    }
}
