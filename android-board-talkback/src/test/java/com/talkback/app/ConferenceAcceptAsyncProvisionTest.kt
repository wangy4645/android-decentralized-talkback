package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceAcceptAsyncProvisionTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50011, m02 to 50012)
        nodeM01 = TestTalkbackNode(context, m01, 50011, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50012, hub, peers)
        nodeM01.start()
        nodeM02.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
    }

    @Test
    fun acceptPendingInvite_completesWithoutSyncProvisionThrow() {
        val channelId = "CONF-ACCEPT-ASYNC"
        nodeM02.runtime.setAutoAcceptConferenceInvites(false)

        nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId
        )
        val pendingDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pendingDeadline) {
            if (nodeM02.runtime.pendingConferenceInvite(channelId) != null) break
            Thread.sleep(50L)
        }
        assertNotNull(nodeM02.runtime.pendingConferenceInvite(channelId))

        assertTrue(nodeM02.runtime.acceptPendingConferenceInvite(channelId))
        assertFalse(
            nodeM02.hasLog { it.contains("Pending conference accept failed") }
        )
        assertFalse(
            nodeM02.hasLog {
                it.contains("Synchronous mesh engine create pending async release")
            }
        )
        assertFalse(nodeM02.hasLog { it.contains("Conference accept failed ch=$channelId") })
        assertTrue(
            nodeM02.waitForLog(timeoutMs = 10_000L) {
                it.contains("Conference invite accepted") ||
                    (it.contains("GROUP_ACCEPT_HANDOFF") && it.contains("result=SUCCESS"))
            }
        )
    }
}
