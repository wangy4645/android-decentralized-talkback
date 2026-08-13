package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupInvitePayloadSemantic
import com.talkback.core.session.GroupInviteSemanticSupport
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

/** #180 F2 A1 — meshCallInternal bootstrap SDP must stamp group-mesh lineage on wire. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupBootstrapMeshCallLineageTest {

    private val m01 = ModuleId("M01")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()

    private lateinit var nodeM01: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50681, m03 to 50683)
        nodeM01 = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50681,
            hub = hub,
            allPeers = peers,
            autoAcceptIncoming = false
        )
        nodeM01.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
    }

    @Test
    fun meshCallInternal_successfulBootstrapInvite_stampsGroupMeshLineage() {
        val channelId = "BOOT-MESH-LINEAGE"
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m03, EndpointId("E03"))),
                channelId
            )
        )

        val payload = nodeM01.runtime.testLastOutboundGroupInvitePayload(sessionId, "M03")
        assertNotNull(payload)
        assertEquals(
            GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE,
            GroupInviteSemanticSupport.classify(payload!!)
        )
        assertNotNull(payload.offerLineageId)
        assertTrue(payload.offerLineageId!!.startsWith("GM"))
        assertTrue(payload.deliveryAttemptId >= 1L)
    }
}
