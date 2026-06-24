package com.talkback.core.signaling

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

class DiscoveryUdpSocket : DiscoveryTransport {
    private var socket: DatagramSocket? = null
    private var listener: ((SignalEnvelope, PeerTarget) -> Unit)? = null
    private val running = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()

    override fun start(listenPort: Int, onMessage: (SignalEnvelope, PeerTarget) -> Unit) {
        this.listener = onMessage
        socket = DatagramSocket(listenPort)
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
                }
            }
        }
    }

    override fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        io.shutdownNow()
        listener = null
    }

    override fun send(target: PeerTarget, envelope: SignalEnvelope) {
        runCatching {
            val data = encode(envelope).toByteArray()
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(target.host), target.port)
            socket?.send(packet)
        }
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

    private fun encodeAddress(address: EndpointAddress): JSONObject =
        JSONObject()
            .put("moduleId", address.moduleId.value)
            .put("endpointId", address.endpointId.value)

    private fun decodeAddress(json: JSONObject): EndpointAddress =
        EndpointAddress(
            ModuleId(json.getString("moduleId")),
            EndpointId(json.getString("endpointId"))
        )
}
