package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-0004 Phase 3: acquire timeout enforces FLOOR_RELEASE (R16).
 * Device calibration targets (ADR-0004): timeout_violation_rate <= 0.1%, false_yield_rate ~ 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FloorAcquireTimeoutEnforceTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50301, m02 to 50302, m03 to 50303)
        nodeM01 = TestTalkbackNode(context, m01, 50301, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50302, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50303, hub, peers, acquireReleaseTimeoutMs = 50L)
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
    fun captureOnBeforeTimeout_noFalseYieldRelease() {
        val channelId = "ACQ-NO-FALSE-YIELD"
        setupGroup(channelId)
        connectAnchorIce(channelId)
        val sessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(sessionId)
        assertTrue(waitForCapturing(nodeM03, sessionId))

        Thread.sleep(120L)

        assertFalse(nodeM03.hasLog { it.contains("ACQUIRE_RELEASE_TIMEOUT") })
        val presence = requireNotNull(nodeM03.runtime.sessionPresenceSnapshot(sessionId))
        assertEquals(nodeM03.localEndpoint.key, presence.protocolFloorOwnerKey)
        assertTrue(nodeM03.runtime.modulePresenceSnapshot().localUplinkGrant)
    }

    @Test
    fun watchdog_firesAfterInjectedDeadline() {
        val fired = AtomicBoolean(false)
        val executor = Executors.newSingleThreadScheduledExecutor()
        var now = 1_000L
        val watchdog = FloorAcquireReleaseWatchdog(
            timeoutMs = { 80L },
            scheduler = executor,
            nowMs = { now },
            onTimeout = { fired.set(true) }
        )
        watchdog.onLocalGrantApplied("s-test", alreadyCapturing = false)
        assertEquals(1_000L, watchdog.grantAtMsForTest("s-test"))
        assertFalse(fired.get())
        Thread.sleep(100L)
        assertTrue(fired.get())
        watchdog.onCaptureStarted("s-test")
        fired.set(false)
        watchdog.onLocalGrantApplied("s-test", alreadyCapturing = false)
        Thread.sleep(30L)
        watchdog.onCaptureStarted("s-test")
        Thread.sleep(100L)
        assertFalse("capture before deadline must cancel timeout", fired.get())
        executor.shutdownNow()
        executor.awaitTermination(1, TimeUnit.SECONDS)
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

    private fun waitForCapturing(node: TestTalkbackNode, sessionId: String): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (node.runtime.testIsSessionCapturing(sessionId)) return true
            Thread.sleep(50)
        }
        return false
    }

    companion object {
        private const val ANCHOR_ID = "M01"
    }
}
