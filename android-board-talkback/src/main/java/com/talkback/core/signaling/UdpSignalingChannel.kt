package com.talkback.core.signaling

import com.talkback.core.discovery.NetworkInterfaceSubnetProvider
import com.talkback.core.signaling.link.LinkQualificationFactSink
import com.talkback.core.signaling.link.SignalingGenerationAuthority
import com.talkback.core.signaling.link.LinkQualificationTrace
import com.talkback.core.signaling.link.QualificationFailureReason
import com.talkback.core.signaling.prr.PrrInboundFactObserver
import com.talkback.core.ptt.FloorRequestCallsiteTracer
import com.talkback.core.util.TalkbackLog
import com.talkback.core.util.OfferDeliveryObservation
import com.talkback.core.util.TransportCapabilityTrace
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UdpSignalingChannel(
    private val lifecycleReporter: SignalingTransportLifecycleReporter? = null,
    private val linkQualificationFacts: LinkQualificationFactSink? = null,
    private val signalingGeneration: SignalingGenerationAuthority? = null,
    private val socketBinder: SignalingSocketBinder? = null,
    private val localModuleId: String = "LOCAL",
    private val localAddressProbe: NetworkInterfaceSubnetProvider = NetworkInterfaceSubnetProvider()
) : SignalingChannel, SignalingTransportBinding {
    private val socketRef = AtomicReference<DatagramSocket?>(null)
    private var listener: ((SignalEnvelope, PeerTarget) -> Unit)? = null
    private val running = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()
    private var localPort: Int = -1
    @Volatile
    private var socketId: Long = 0L
    private var hadSocket: Boolean = false
    private val lastInboundMsByIp = ConcurrentHashMap<String, Long>()
    private val outboundSinceLastInboundByIp = ConcurrentHashMap<String, AtomicInteger>()
    private val lastAsymmetryLogMsByIp = ConcurrentHashMap<String, Long>()
    @Volatile
    private var socketBoundAtMs: Long = 0L
    @Volatile
    private var rebindAtMs: Long = 0L
    @Volatile
    private var rebindGeneration: Long = 0L
    @Volatile
    private var qualificationRebindGeneration: Long = 0L
    @Volatile
    private var lastInboundOnCurrentSocketMs: Long = 0L
    @Volatile
    private var awaitingFirstInboundSocketId: Long = 0L
    @Volatile
    private var awaitingFirstOutboundSocketId: Long = 0L
    @Volatile
    private var hasOutboundAfterRebind: Boolean = false
    @Volatile
    private var hasInboundAfterRebind: Boolean = false
    @Volatile
    private var bidirectionalConfirmedGeneration: Long? = null
    @Volatile
    private var lastBlockingSocketId: Long = 0L
    @Volatile
    private var lastBlockingLogMs: Long = 0L

    companion object {
        private const val PATH_SILENCE_THRESHOLD_MS = 10_000L
        private const val PATH_ASYMMETRY_LOG_INTERVAL_MS = 30_000L
        private const val RECEIVE_LOOP_BLOCKING_LOG_INTERVAL_MS = 30_000L
    }

    override fun start(localPort: Int) {
        this.localPort = localPort
        running.set(true)
        io.submit {
            val buf = ByteArray(8192)
            while (running.get()) {
                val activeSocket = socketRef.get()
                if (activeSocket == null) {
                    Thread.sleep(50)
                    continue
                }
                val packet = DatagramPacket(buf, buf.size)
                observeReceiveLoopBlocking()
                val received = runCatching { activeSocket.receive(packet) }
                if (received.isFailure) {
                    if (!running.get()) continue
                    TalkbackLog.w(
                        "SIGNAL_DATAGRAM_RECEIVE_FAILED socketId=$socketId " +
                            "err=${received.exceptionOrNull()?.message}"
                    )
                    continue
                }
                val source = PeerTarget(packet.address.hostAddress ?: "", packet.port)
                observeFirstInboundAfterRebind(source.host, source.port)
                OfferDeliveryObservation.udpDatagramReceived(
                    srcHost = source.host,
                    srcPort = source.port,
                    socketId = socketId,
                    bytes = packet.length
                )
                val body = String(packet.data, packet.offset, packet.length)
                val decoded = runCatching { decode(body) }
                val envelope = decoded.getOrNull()
                if (envelope == null) {
                    TalkbackLog.w(
                        "SIGNAL_DATAGRAM_DECODE_FAILED src=${source.host}:${source.port} " +
                            "bytes=${packet.length} err=${decoded.exceptionOrNull()?.message}"
                    )
                    continue
                }
                OfferDeliveryObservation.signalEnvelopeDecoded(
                    envelope = envelope,
                    srcHost = source.host,
                    srcPort = source.port,
                    socketId = socketId
                )
                val packetLocalIp = localIp(socketRef.get())
                TransportCapabilityTrace.datagramReceived(
                    signalType = envelope.type.name,
                    socketId = socketId,
                    localIp = packetLocalIp,
                    srcIp = source.host,
                    srcPort = source.port,
                    networkId = TransportCapabilityTrace.currentNetworkId(),
                    bytes = packet.length
                )
                OfferDeliveryObservation.classifyInbound(
                    envelope = envelope,
                    srcHost = source.host,
                    srcPort = source.port,
                    socketId = socketId
                )
                if (envelope.type == SignalType.GROUP_JOIN) {
                    val (lineage, attempt, gen) = OfferDeliveryObservation.correlationFromEnvelope(envelope)
                    OfferDeliveryObservation.emit(
                        stage = OfferDeliveryObservation.Stage.REMOTE_RECEIVE,
                        remoteModuleId = envelope.from.moduleId.value,
                        pathKind = OfferDeliveryObservation.pathKindOf(envelope),
                        signalType = envelope.type.name,
                        offerLineageId = lineage,
                        sessionId = envelope.sessionId,
                        restartAttemptId = attempt,
                        transportGeneration = gen,
                        detail = "src=${source.host}:${source.port} socketId=$socketId"
                    )
                }
                LinkQualificationTrace.remoteReceiveObserved(
                    localModuleId = localModuleId,
                    remoteModuleId = envelope.from.moduleId.value,
                    socketId = socketId,
                    rebindGeneration = qualificationRebindGeneration,
                    signalType = envelope.type.name,
                    srcIp = source.host,
                    srcPort = source.port
                )
                PrrInboundFactObserver.observe(
                    envelope = envelope,
                    source = source,
                    localEpoch = qualificationRebindGeneration,
                    socketId = socketId
                )
                observeInboundResumed(source.host, source.port)
                val stamped = envelope.copy(receiveGeneration = qualificationRebindGeneration, receiveSocketId = socketId)
                runCatching { listener?.invoke(stamped, source) }.onFailure { error ->
                    TalkbackLog.e(
                        "SIGNAL_DISPATCH_FAILED type=${envelope.type} " +
                            "from=${envelope.from.moduleId.value} src=${source.host}:${source.port}",
                        error
                    )
                }
            }
        }
    }

    override fun stop() {
        running.set(false)
        val stoppingSocketId = socketId
        invalidateBinding("stop")
        if (stoppingSocketId > 0L) {
            lifecycleReporter?.onReceiveLoopStopped(stoppingSocketId)
        }
        io.shutdownNow()
    }

    override fun send(target: PeerTarget, envelope: SignalEnvelope) {
        sendInternal(target, envelope, countsAsLocalOutbound = true)
    }

    override fun sendRepairAnnounce(target: PeerTarget, envelope: SignalEnvelope) {
        sendInternal(target, envelope, countsAsLocalOutbound = false)
    }

    private fun sendInternal(target: PeerTarget, envelope: SignalEnvelope, countsAsLocalOutbound: Boolean) {
        ensureSocketBound("send")
        val data = runCatching { encode(envelope).toByteArray() }.getOrElse { byteArrayOf() }
        val dst = "${target.host}:${target.port}"
        val networkId = TransportCapabilityTrace.currentNetworkId()
        TransportCapabilityTrace.datagramWriteAccepted(
            type = envelope.type.name,
            dst = dst,
            bytes = data.size,
            socketId = socketId,
            networkId = networkId
        )
        val activeSocket = socketRef.get()
        val result = runCatching {
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(target.host), target.port)
            activeSocket?.send(packet) ?: error("signaling socket unavailable")
            packet
        }
        if (result.isSuccess) {
            val packet = result.getOrThrow()
            val dstIp = packet.address?.hostAddress ?: target.host
            TransportCapabilityTrace.datagramSent(
                signalType = envelope.type.name,
                socketId = socketId,
                localIp = localIp(activeSocket),
                dstIp = dstIp,
                dstPort = packet.port,
                networkId = networkId,
                bytes = data.size,
                sessionId = envelope.sessionId.takeIf { it.isNotBlank() },
                nonce = envelope.nonce.takeIf { it.isNotBlank() }
            )
            if (envelope.type == SignalType.GROUP_JOIN) {
                val (lineage, attempt, gen) = OfferDeliveryObservation.correlationFromEnvelope(envelope)
                OfferDeliveryObservation.emit(
                    stage = OfferDeliveryObservation.Stage.LOCAL_ACCEPT,
                    remoteModuleId = envelope.to?.moduleId?.value ?: "UNKNOWN",
                    pathKind = OfferDeliveryObservation.pathKindOf(envelope),
                    signalType = envelope.type.name,
                    offerLineageId = lineage,
                    sessionId = envelope.sessionId,
                    restartAttemptId = attempt,
                    transportGeneration = gen,
                    detail = "dst=$dstIp:${packet.port} socketId=$socketId"
                )
            }
            observePathAsymmetry(dstIp, packet.port, envelope.type.name)
            if (countsAsLocalOutbound) {
                observeFirstOutboundAfterRebind(dstIp, packet.port)
            }
        }
        FloorRequestCallsiteTracer.recordUdpWrite(
            sendTarget = target,
            envelope = envelope,
            sendResult = if (result.isSuccess) "UDP_OK" else "UDP_FAIL:${result.exceptionOrNull()?.message}"
        )
        result.onFailure {
            TalkbackLog.w(
                "SIGNAL_DATAGRAM_SEND_FAILED socketId=$socketId dst=$dst err=${it.message}"
            )
        }
    }

    override fun onMessage(listener: (SignalEnvelope, PeerTarget) -> Unit) {
        this.listener = listener
    }

    @Synchronized
    override fun invalidateBinding(reason: String) {
        val closingSocketId = socketId
        val oldSocket = socketRef.getAndSet(null)
        oldSocket?.close()
        socketId = 0L
        if (closingSocketId > 0L) {
            lifecycleReporter?.onSocketClosed(closingSocketId, reason)
        }
    }

    @Synchronized
    override fun rebindBinding(networkId: String, reason: String) {
        if (localPort <= 0) return
        val oldSocket = socketRef.getAndSet(null)
        oldSocket?.close()
        val oldSocketId = socketId
        val newSocketId = TransportCapabilityTrace.nextSocketId()
        val newSocket = runCatching { DatagramSocket(localPort) }.getOrElse { error ->
            TalkbackLog.e(
                "SIGNAL_SOCKET_REBIND_FAILED port=$localPort reason=$reason networkId=$networkId",
                error
            )
            return
        }
        val boundNetworkId = socketBinder?.bindSocket(newSocket)
            ?: SignalingTransportManager.BOUND_NETWORK_UNBOUND
        socketRef.set(newSocket)
        socketId = newSocketId
        val now = System.currentTimeMillis()
        qualificationRebindGeneration = signalingGeneration?.advanceRebindGeneration() ?: run {
            rebindGeneration += 1L
            rebindGeneration
        }
        rebindGeneration = qualificationRebindGeneration
        socketBoundAtMs = now
        rebindAtMs = now
        lastInboundOnCurrentSocketMs = 0L
        awaitingFirstInboundSocketId = newSocketId
        awaitingFirstOutboundSocketId = newSocketId
        hasOutboundAfterRebind = false
        hasInboundAfterRebind = false
        bidirectionalConfirmedGeneration = null
        lastBlockingSocketId = 0L
        lastBlockingLogMs = 0L
        TransportCapabilityTrace.socketBound(
            socketId = newSocketId,
            networkId = networkId,
            localAddress = localIp(newSocket),
            boundNetworkId = boundNetworkId,
            rebindGeneration = qualificationRebindGeneration
        )
        linkQualificationFacts?.onSocketBound(newSocketId, qualificationRebindGeneration, networkId)
        if (reason == "qualification_repair") {
            LinkQualificationTrace.linkRepairSocketContext(
                phase = "AFTER_REBIND_SOCKET",
                repairReason = QualificationFailureReason.QUALIFICATION_TIMEOUT,
                repairAttempt = -1,
                beforeSocketId = oldSocketId,
                afterSocketId = newSocketId,
                rebindGeneration = qualificationRebindGeneration,
                networkId = networkId,
                boundNetwork = boundNetworkId
            )
        }
        if (!hadSocket) {
            hadSocket = true
            lifecycleReporter?.onSocketCreated(newSocketId, localPort)
        } else {
            lifecycleReporter?.onSocketRebind(newSocketId, localPort, reason, boundNetworkId)
        }
        lifecycleReporter?.onSocketBound(newSocketId, localPort, boundNetworkId)
        if (running.get()) {
            lifecycleReporter?.onReceiveLoopStarted(newSocketId)
            linkQualificationFacts?.onReceiveLoopStarted(newSocketId, qualificationRebindGeneration)
        }
    }

    @Synchronized
    private fun ensureSocketBound(reason: String) {
        if (socketRef.get() != null || localPort <= 0) return
        rebindBinding(
            networkId = TransportCapabilityTrace.currentNetworkId(),
            reason = "recover_$reason"
        )
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


    private fun observeInboundResumed(srcIp: String, srcPort: Int) {
        val now = System.currentTimeMillis()
        val previous = lastInboundMsByIp.put(srcIp, now)
        if (previous != null) {
            val silenceMs = now - previous
            if (silenceMs >= PATH_SILENCE_THRESHOLD_MS) {
                TransportCapabilityTrace.inboundResumed(srcIp, srcPort, silenceMs)
            }
        }
        outboundSinceLastInboundByIp[srcIp] = AtomicInteger(0)
    }

    private fun observePathAsymmetry(dstIp: String, dstPort: Int, signalType: String) {
        val lastInbound = lastInboundMsByIp[dstIp] ?: return
        val now = System.currentTimeMillis()
        val inboundAge = now - lastInbound
        if (inboundAge < PATH_SILENCE_THRESHOLD_MS) return
        val outboundCount = outboundSinceLastInboundByIp
            .computeIfAbsent(dstIp) { AtomicInteger(0) }
            .incrementAndGet()
        val lastLog = lastAsymmetryLogMsByIp[dstIp] ?: 0L
        if (outboundCount == 1 || now - lastLog >= PATH_ASYMMETRY_LOG_INTERVAL_MS) {
            lastAsymmetryLogMsByIp[dstIp] = now
            TransportCapabilityTrace.pathAsymmetryObserved(
                dstIp = dstIp,
                dstPort = dstPort,
                lastInboundAgeMs = inboundAge,
                outboundSinceLastInbound = outboundCount,
                signalType = signalType
            )
        }
    }
    private fun observeReceiveLoopBlocking() {
        val activeSocketId = socketId
        if (activeSocketId <= 0L) return
        val now = System.currentTimeMillis()
        val boundAt = socketBoundAtMs
        val lastInbound = lastInboundOnCurrentSocketMs
        val elapsed = when {
            lastInbound > 0L -> now - lastInbound
            boundAt > 0L -> now - boundAt
            else -> 0L
        }
        val isNewSocket = activeSocketId != lastBlockingSocketId
        if (!isNewSocket && now - lastBlockingLogMs < RECEIVE_LOOP_BLOCKING_LOG_INTERVAL_MS) return
        lastBlockingSocketId = activeSocketId
        lastBlockingLogMs = now
        TransportCapabilityTrace.receiveLoopBlocking(
            socketId = activeSocketId,
            lastInboundElapsedMs = elapsed,
            networkId = TransportCapabilityTrace.currentNetworkId()
        )
    }

    private fun observeFirstInboundAfterRebind(srcIp: String, srcPort: Int) {
        val now = System.currentTimeMillis()
        lastInboundOnCurrentSocketMs = now
        val awaitingSocketId = awaitingFirstInboundSocketId
        if (awaitingSocketId > 0L && socketId == awaitingSocketId) {
            val deltaFromRebindMs = deltaFromRebindMs(now)
            awaitingFirstInboundSocketId = 0L
            hasInboundAfterRebind = true
            TransportCapabilityTrace.firstInboundAfterRebind(
                socketId = socketId,
                networkId = TransportCapabilityTrace.currentNetworkId(),
                sourceAddress = "$srcIp:$srcPort",
                deltaFromRebindMs = deltaFromRebindMs,
                rebindGeneration = qualificationRebindGeneration
            )
            linkQualificationFacts?.onFirstInboundAfterRebind(socketId, qualificationRebindGeneration)
            observeBidirectionalConfirmed(now)
        }
    }

    private fun observeFirstOutboundAfterRebind(dstIp: String, dstPort: Int) {
        val awaitingSocketId = awaitingFirstOutboundSocketId
        if (awaitingSocketId <= 0L || socketId != awaitingSocketId) return
        val now = System.currentTimeMillis()
        awaitingFirstOutboundSocketId = 0L
        hasOutboundAfterRebind = true
        TransportCapabilityTrace.firstOutboundAfterRebind(
            socketId = socketId,
            networkId = TransportCapabilityTrace.currentNetworkId(),
            remote = "$dstIp:$dstPort",
            deltaFromRebindMs = deltaFromRebindMs(now),
            rebindGeneration = qualificationRebindGeneration
        )
        linkQualificationFacts?.onFirstOutboundAfterRebind(socketId, qualificationRebindGeneration)
        observeBidirectionalConfirmed(now)
    }

    private fun observeBidirectionalConfirmed(nowMs: Long) {
        if (!hasOutboundAfterRebind || !hasInboundAfterRebind) return
        if (bidirectionalConfirmedGeneration == qualificationRebindGeneration) return
        bidirectionalConfirmedGeneration = qualificationRebindGeneration
        TransportCapabilityTrace.bidirectionalConfirmed(
            socketId = socketId,
            networkId = TransportCapabilityTrace.currentNetworkId(),
            hasOutboundAfterRebind = true,
            hasInboundAfterRebind = true,
            deltaFromRebindMs = deltaFromRebindMs(nowMs),
            rebindGeneration = qualificationRebindGeneration
        )
    }

    private fun deltaFromRebindMs(nowMs: Long): Long {
        val rebindAt = rebindAtMs
        return if (rebindAt > 0L) nowMs - rebindAt else 0L
    }

    private fun localIp(socket: DatagramSocket?): String {
        if (socket == null) return "unknown"
        val fromSocket = socket.localAddress?.hostAddress?.takeIf { it.isNotBlank() }
        if (fromSocket != null && fromSocket != "::" && fromSocket != "0:0:0:0:0:0:0:0") {
            return fromSocket
        }
        return localAddressProbe.localHostAddress() ?: fromSocket ?: "unknown"
    }
}
