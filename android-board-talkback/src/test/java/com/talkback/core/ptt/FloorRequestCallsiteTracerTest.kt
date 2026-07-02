package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.transport.TransportBinding
import com.talkback.core.transport.TransportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FloorRequestCallsiteTracerTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        FloorRequestCallsiteTracer.resetForTests { lines.add(it) }
    }

    @Test
    fun recordResolve_ok_emitsCoreFields() {
        val m01 = ModuleId("M01")
        val endpoint = EndpointAddress(m01, EndpointId("E01"))
        val peer = PeerTarget("192.168.1.1", 9000)
        val transport = TransportBinding(m01, peer.host, peer.port, 3L, TransportSource.VERIFIED_HELLO)
        val route = FloorAuthorityRoute.resolve(m01, endpoint, 3L, transport)
        FloorRequestCallsiteTracer.recordResolve(
            sessionId = "SID-1",
            localModuleId = "M03",
            coordinatorHash = 111,
            sessionHash = 222,
            authorityModuleId = "M01",
            route = route,
            transport = transport
        )
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.contains("FLOOR_REQUEST_CALLSITE"))
        assertTrue(line.contains("stage=RESOLVE"))
        assertTrue(line.contains("resolvedPeer=192.168.1.1:9000"))
        assertTrue(line.contains("sendTarget=192.168.1.1:9000"))
        assertTrue(line.contains("envelopeTo=M01"))
        assertTrue(line.contains("threadId="))
        assertTrue(line.contains("callStackHash="))
    }

    @Test
    fun recordSendSignal_ignoresNonFloorRequest() {
        val envelope = SignalEnvelope(
            type = SignalType.HELLO,
            from = EndpointAddress(ModuleId("M03"), EndpointId("E01")),
            to = null,
            sessionId = "SID-1",
            timestampMs = 1L,
            payload = "",
            nonce = "",
            signature = ""
        )
        FloorRequestCallsiteTracer.recordSendSignal(
            sessionId = "SID-1",
            localModuleId = "M03",
            coordinatorHash = 1,
            sessionHash = 2,
            sendTarget = PeerTarget("10.0.0.2", 8000),
            envelope = envelope,
            sendResult = "SEND_OK"
        )
        assertTrue(lines.isEmpty())
    }

    @Test
    fun callStackHash_isNonEmptyHex() {
        val hash = FloorRequestCallsiteTracer.callStackHash()
        assertFalse(hash.isEmpty())
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
