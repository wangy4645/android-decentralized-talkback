package com.talkback.core.signaling

import com.talkback.core.ptt.FloorRequestCallsiteTracer
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UdpSignalingChannel : SignalingChannel {
    private var socket: DatagramSocket? = null
    private var listener: ((SignalEnvelope, PeerTarget) -> Unit)? = null
    private val running = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()
    private var localPort: Int = -1

    override fun start(localPort: Int) {
        this.localPort = localPort
        recreateSocket()
        running.set(true)
        io.submit {
            val buf = ByteArray(8192)
            while (running.get()) {
                runCatching {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet) ?: return@runCatching
                    val body = String(packet.data, packet.offset, packet.length)
                    val envelope = decode(body)
                    listener?.invoke(envelope, PeerTarget(packet.address.hostAddress ?: "", packet.port))
                }.onFailure {
                    if (!running.get()) return@onFailure
                    recreateSocket()
                }
            }
        }
    }

    override fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        io.shutdownNow()
    }

    override fun send(target: PeerTarget, envelope: SignalEnvelope) {
        val result = runCatching {
            val data = encode(envelope).toByteArray()
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(target.host), target.port)
            socket?.send(packet)
        }
        FloorRequestCallsiteTracer.recordUdpWrite(
            sendTarget = target,
            envelope = envelope,
            sendResult = if (result.isSuccess) "UDP_OK" else "UDP_FAIL:${result.exceptionOrNull()?.message}"
        )
        result.onFailure {
            if (running.get()) {
                recreateSocket()
            }
        }
    }

    override fun onMessage(listener: (SignalEnvelope, PeerTarget) -> Unit) {
        this.listener = listener
    }

    private fun encode(msg: SignalEnvelope): String {
        val json = JSONObject()
        json.put("type", msg.type.name)
        json.put("sessionId", msg.sessionId)
        json.put("timestampMs", msg.timestampMs)
        json.put("payload", msg.payload)
        json.put("nonce", msg.nonce)
        json.put("signature", msg.signature)
        json.put("from", encodeAddress(msg.from))
        json.put("to", msg.to?.let(::encodeAddress))
        return json.toString()
    }

    private fun decode(raw: String): SignalEnvelope {
        val json = JSONObject(raw)
        return SignalEnvelope(
            type = SignalType.valueOf(json.getString("type")),
            from = decodeAddress(json.getJSONObject("from")),
            to = json.optJSONObject("to")?.let(::decodeAddress),
            sessionId = json.getString("sessionId"),
            timestampMs = json.getLong("timestampMs"),
            payload = json.optString("payload"),
            nonce = json.optString("nonce"),
            signature = json.optString("signature")
        )
    }

    private fun encodeAddress(address: EndpointAddress): JSONObject {
        return JSONObject()
            .put("moduleId", address.moduleId.value)
            .put("endpointId", address.endpointId.value)
    }

    private fun decodeAddress(json: JSONObject): EndpointAddress {
        return EndpointAddress(
            ModuleId(json.getString("moduleId")),
            EndpointId(json.getString("endpointId"))
        )
    }

    @Synchronized
    private fun recreateSocket() {
        if (localPort <= 0) return
        runCatching { socket?.close() }
        socket = DatagramSocket(localPort)
    }
}
