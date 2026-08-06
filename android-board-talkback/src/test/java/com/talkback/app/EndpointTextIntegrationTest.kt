package com.talkback.app

import com.talkback.core.endpointtext.EndpointTextController
import com.talkback.core.endpointtext.EndpointTextEvent
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EndpointTextIntegrationTest {
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
            m01 to 51001,
            m02 to 51002,
            m03 to 51003
        )
        nodeM01 = TestTalkbackNode(context, m01, 51001, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 51002, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 51003, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
        Thread.sleep(200L)
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun case1_happyPath_m01ToM02_deliversTextAndSenderKey_withoutSession() {
        val received = CopyOnWriteArrayList<EndpointTextEvent>()
        val latch = CountDownLatch(1)
        nodeM02.runtime.onEndpointTextReceived = {
            received.add(it)
            latch.countDown()
        }

        assertTrue(nodeM01.runtime.activeSessionIds().isEmpty())
        assertTrue(nodeM02.runtime.activeSessionIds().isEmpty())

        val result = nodeM01.runtime.sendEndpointText(
            from = nodeM01.localEndpoint,
            to = EndpointAddress(m02, EndpointId("E01")),
            text = "改频道 12"
        )
        assertTrue(result.isSuccess)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, received.size)
        assertEquals("改频道 12", received[0].text)
        assertEquals(nodeM01.localEndpoint.key, received[0].from.key)
        assertEquals(EndpointAddress(m02, EndpointId("E01")).key, received[0].to.key)
        assertTrue(nodeM01.runtime.activeSessionIds().isEmpty())
        assertTrue(nodeM02.runtime.activeSessionIds().isEmpty())
    }

    @Test
    fun case2_sendDuringGroupActiveFloorHeld_sessionAndFloorUnchanged() {
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                "CH-ET"
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(500L)
        nodeM01.pressPtt(sessionId)
        Thread.sleep(400L)
        val floorBefore = nodeM01.runtime.sessionSnapshots()
            .first { it.sessionId == sessionId }
            .protocolFloorOwnerKey
        assertNotNull(floorBefore)
        val sessionsBefore = nodeM01.runtime.activeSessionIds()
        val pttBefore = nodeM01.runtime.sessionSnapshots().first { it.sessionId == sessionId }.localPttState

        val latch = CountDownLatch(1)
        nodeM02.runtime.onEndpointTextReceived = { latch.countDown() }
        val result = nodeM01.runtime.sendEndpointText(
            from = nodeM01.localEndpoint,
            to = EndpointAddress(m02, EndpointId("E01")),
            text = "停止作业"
        )
        assertTrue(result.isSuccess)
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertEquals(sessionsBefore, nodeM01.runtime.activeSessionIds())
        val snapAfter = nodeM01.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals(floorBefore, snapAfter.protocolFloorOwnerKey)
        assertEquals(pttBefore, snapAfter.localPttState)
        nodeM01.releasePtt(sessionId)
    }

    @Test
    fun case3_receiveWhileUnicastActive_noRejectNoNewSession() {
        val unicastId = nodeM02.runtime.call(
            nodeM02.localEndpoint,
            EndpointAddress(m03, EndpointId("E01"))
        )
        assertTrue(nodeM03.waitForLog { it.contains("Call accepted") })
        assertEquals(1, nodeM02.runtime.activeSessionIds().size)

        val received = CopyOnWriteArrayList<EndpointTextEvent>()
        val latch = CountDownLatch(1)
        nodeM02.runtime.onEndpointTextReceived = {
            received.add(it)
            latch.countDown()
        }
        val mark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        val result = nodeM01.runtime.sendEndpointText(
            from = nodeM01.localEndpoint,
            to = EndpointAddress(m02, EndpointId("E01")),
            text = "busy but deliver"
        )
        assertTrue(result.isSuccess)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, received.size)
        assertEquals(listOf(unicastId), nodeM02.runtime.activeSessionIds())
        assertFalse(nodeM02.waitForLogSince(mark, timeoutMs = 800L) { it.contains("BUSY") })
        assertFalse(
            nodeM02.hasLog { line ->
                line.contains("CALL_REJECT") && line.contains("BUSY")
            }
        )
    }

    @Test
    fun case4_duplicateMessageId_deliveredOnce() {
        val received = CopyOnWriteArrayList<EndpointTextEvent>()
        nodeM02.runtime.onEndpointTextReceived = { received.add(it) }
        val messageId = UUID.randomUUID().toString()
        val fromPeer = TestTalkbackNode.peerTarget(51001)
        nodeM02.runtime.testInjectEndpointText(
            from = nodeM01.localEndpoint,
            to = EndpointAddress(m02, EndpointId("E01")),
            messageId = messageId,
            text = "once",
            fromPeer = fromPeer
        )
        nodeM02.runtime.testInjectEndpointText(
            from = nodeM01.localEndpoint,
            to = EndpointAddress(m02, EndpointId("E01")),
            messageId = messageId,
            text = "once",
            fromPeer = fromPeer
        )
        Thread.sleep(300L)
        assertEquals(1, received.size)
        assertTrue(nodeM02.hasLog { it.contains("ENDPOINT_TEXT dedup") && it.contains(messageId) })
    }

    @Test
    fun case5_secondSendWithin1s_silentlyDropped() {
        val received = CopyOnWriteArrayList<EndpointTextEvent>()
        val latch = CountDownLatch(1)
        nodeM02.runtime.onEndpointTextReceived = {
            received.add(it)
            latch.countDown()
        }
        val to = EndpointAddress(m02, EndpointId("E01"))
        assertTrue(
            nodeM01.runtime.sendEndpointText(nodeM01.localEndpoint, to, "first").isSuccess
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(
            nodeM01.runtime.sendEndpointText(nodeM01.localEndpoint, to, "second").isSuccess
        )
        Thread.sleep(400L)
        assertEquals(1, received.size)
        assertEquals("first", received[0].text)
        assertTrue(nodeM01.hasLog { it.contains("ENDPOINT_TEXT rate_limited") })
    }

    @Test
    fun rejectsTextOver256Chars_andUnreachableModule_withoutWireDelivery() {
        val received = CopyOnWriteArrayList<EndpointTextEvent>()
        nodeM02.runtime.onEndpointTextReceived = { received.add(it) }

        val tooLong = "x".repeat(EndpointTextController.MAX_TEXT_CHARS + 1)
        val longResult = nodeM01.runtime.sendEndpointText(
            nodeM01.localEndpoint,
            EndpointAddress(m02, EndpointId("E01")),
            tooLong
        )
        assertTrue(longResult.isFailure)
        assertTrue(
            longResult.exceptionOrNull()?.message?.contains(EndpointTextController.REASON_TEXT_TOO_LONG) == true
        )

        val unreachable = nodeM01.runtime.sendEndpointText(
            nodeM01.localEndpoint,
            EndpointAddress(ModuleId("M99"), EndpointId("E01")),
            "hello"
        )
        assertTrue(unreachable.isFailure)
        assertTrue(
            unreachable.exceptionOrNull()?.message?.contains(EndpointTextController.REASON_UNREACHABLE) == true
        )
        Thread.sleep(200L)
        assertTrue(received.isEmpty())
    }
}
