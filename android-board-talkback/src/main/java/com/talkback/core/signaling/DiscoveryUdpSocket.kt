package com.talkback.core.signaling

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.util.TalkbackLog
import org.json.JSONObject
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DiscoveryUdpSocket(
    private val socketBinder: SignalingSocketBinder? = null,
    private val socketOpener: (Int) -> DatagramSocket = { port -> DatagramSocket(port) },
    private val scheduler: ScheduledExecutorService? = SHARED_RETRY_SCHEDULER,
    private val postCloseDelayMs: Long = DEFAULT_POST_CLOSE_DELAY_MS,
    private val retryDelaysMs: LongArray = DEFAULT_RETRY_DELAYS_MS
) : DiscoveryTransport, SignalingTransportBinding {
    private val socketRef = AtomicReference<DatagramSocket?>(null)
    private var listener: ((SignalEnvelope, PeerTarget) -> Unit)? = null
    private val running = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()
    private var listenPort: Int = -1
    @Volatile
    private var retryAttempt: Int = 0
    @Volatile
    private var pendingRetry: ScheduledFuture<*>? = null
    @Volatile
    private var lastNetworkId: String = "none"
    @Volatile
    private var lastRebindReason: String = "unknown"

    override fun start(listenPort: Int, onMessage: (SignalEnvelope, PeerTarget) -> Unit) {
        this.listenPort = listenPort
        this.listener = onMessage
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
                val received = runCatching { activeSocket.receive(packet) }
                if (received.isFailure) {
                    if (!running.get()) continue
                    TalkbackLog.w(
                        "DISCOVERY_DATAGRAM_RECEIVE_FAILED port=$listenPort " +
                            "err=${received.exceptionOrNull()?.message}"
                    )
                    continue
                }
                runCatching {
                    val body = String(packet.data, packet.offset, packet.length)
                    val envelope = decode(body)
                    listener?.invoke(
                        envelope,
                        PeerTarget(packet.address.hostAddress ?: "", packet.port)
                    )
                }.onFailure { error ->
                    TalkbackLog.w(
                        "DISCOVERY_DATAGRAM_DECODE_FAILED port=$listenPort " +
                            "bytes=${packet.length} err=${error.message}"
                    )
                }
            }
        }
    }

    override fun stop() {
        running.set(false)
        cancelPendingRetry()
        invalidateBinding("stop")
        io.shutdownNow()
        listener = null
        listenPort = -1
        retryAttempt = 0
    }

    override fun send(target: PeerTarget, envelope: SignalEnvelope) {
        ensureSocketBound()
        val activeSocket = socketRef.get() ?: return
        runCatching {
            val data = encode(envelope).toByteArray()
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(target.host), target.port)
            activeSocket.send(packet)
        }.onFailure {
            TalkbackLog.w(
                "DISCOVERY_DATAGRAM_SEND_FAILED port=$listenPort dst=${target.host}:${target.port} " +
                    "err=${it.message}"
            )
        }
    }

    @Synchronized
    override fun invalidateBinding(reason: String) {
        cancelPendingRetry()
        closeActiveSocket()
    }

    @Synchronized
    override fun rebindBinding(networkId: String, reason: String) {
        if (listenPort <= 0) return
        lastNetworkId = networkId
        lastRebindReason = reason
        cancelPendingRetry()
        retryAttempt = 0
        attemptRebind(networkId, reason)
    }

    @Synchronized
    private fun ensureSocketBound() {
        if (socketRef.get() != null || listenPort <= 0) return
        lastNetworkId = "recover"
        lastRebindReason = "send_without_socket"
        cancelPendingRetry()
        retryAttempt = 0
        attemptRebind(lastNetworkId, lastRebindReason)
    }

    @Synchronized
    private fun attemptRebind(networkId: String, reason: String) {
        if (!running.get() && listenPort <= 0) return
        val attempt = retryAttempt + 1
        DiscoveryTransportTrace.rebindRequested(listenPort, networkId, reason, attempt)
        closeActiveSocket()
        if (postCloseDelayMs > 0L) {
            Thread.sleep(postCloseDelayMs)
        }
        val newSocket = runCatching { socketOpener(listenPort) }.getOrElse { error ->
            handleBindFailure(networkId, reason, attempt, error)
            return
        }
        socketBinder?.bindSocket(newSocket)
        socketRef.set(newSocket)
        retryAttempt = 0
        DiscoveryTransportTrace.rebindSuccess(listenPort, networkId, reason, attempt)
        DiscoveryTransportTrace.ready(listenPort, networkId, reason)
    }

    @Synchronized
    private fun handleBindFailure(networkId: String, reason: String, attempt: Int, error: Throwable) {
        val label = bindErrorLabel(error)
        DiscoveryTransportTrace.rebindFailed(listenPort, networkId, reason, attempt, label)
        if (!shouldRetryBind(error)) {
            TalkbackLog.e(
                "DISCOVERY_SOCKET_REBIND_FAILED port=$listenPort reason=$reason networkId=$networkId",
                error
            )
            return
        }
        scheduleRebindRetry(networkId, reason)
    }

    @Synchronized
    private fun scheduleRebindRetry(networkId: String, reason: String) {
        if (scheduler == null) return
        if (!running.get() && listenPort <= 0) return
        retryAttempt++
        val delayMs = retryDelaysMs[minOf(retryAttempt - 1, retryDelaysMs.lastIndex)]
        DiscoveryTransportTrace.rebindRetryScheduled(
            port = listenPort,
            networkId = networkId,
            reason = reason,
            attempt = retryAttempt,
            delayMs = delayMs
        )
        cancelPendingRetry()
        pendingRetry = scheduler.schedule(
            {
                synchronized(this) {
                    attemptRebind(networkId, reason)
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun cancelPendingRetry() {
        pendingRetry?.cancel(false)
        pendingRetry = null
    }

    @Synchronized
    private fun closeActiveSocket() {
        socketRef.getAndSet(null)?.close()
    }

    internal fun hasPendingRetry(): Boolean = pendingRetry?.isDone == false

    internal fun retryAttemptCount(): Int = retryAttempt

    private fun shouldRetryBind(error: Throwable): Boolean =
        error is BindException ||
            (error is SocketException && error.message?.contains("EADDRINUSE", ignoreCase = true) == true)

    private fun bindErrorLabel(error: Throwable): String =
        when (error) {
            is BindException -> "EADDRINUSE"
            else -> error.message ?: error.javaClass.simpleName
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

    companion object {
        const val DEFAULT_POST_CLOSE_DELAY_MS = 50L
        val DEFAULT_RETRY_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        private val SHARED_RETRY_SCHEDULER: ScheduledExecutorService? =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "discovery-rebind-retry").apply { isDaemon = true }
            }
    }
}