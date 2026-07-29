package com.talkback.core.util

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** C5/C6 trace-only: Network -> Socket -> Datagram. Does not affect recovery logic. */
object TransportCapabilityTrace {
    private val socketIdGenerator = AtomicLong(0)
    private val currentNetworkIdRef = AtomicReference("none")

    fun nextSocketId(): Long = socketIdGenerator.incrementAndGet()

    fun currentNetworkId(): String = currentNetworkIdRef.get()

    fun setCurrentNetworkId(networkId: String) {
        currentNetworkIdRef.set(networkId)
    }

    fun networkCapabilityAvailable(interfaceName: String, networkId: String) {
        currentNetworkIdRef.set(networkId)
        TalkbackLog.i(
            "TransportCapabilityTrace NETWORK_CAPABILITY_AVAILABLE interface=$interfaceName networkId=$networkId"
        )
    }

    fun networkCapabilityLost(interfaceName: String, networkId: String) {
        TalkbackLog.i(
            "TransportCapabilityTrace NETWORK_CAPABILITY_LOST interface=$interfaceName networkId=$networkId"
        )
    }

    fun networkChanged(oldNetworkId: String, newNetworkId: String, reason: String) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_NETWORK_CHANGED oldNetworkId=$oldNetworkId " +
                "newNetworkId=$newNetworkId reason=$reason"
        )
    }

    fun socketCreate(socketId: Long, port: Int, boundNetworkId: String, transportInstanceId: Long) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_SOCKET_CREATE socketId=$socketId port=$port " +
                "boundNetworkId=$boundNetworkId transportInstanceId=$transportInstanceId"
        )
    }

    fun socketClose(socketId: Long, reason: String, transportInstanceId: Long) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_SOCKET_CLOSE socketId=$socketId reason=$reason " +
                "transportInstanceId=$transportInstanceId"
        )
    }

    fun socketRebind(socketId: Long, port: Int, reason: String, boundNetworkId: String, transportInstanceId: Long) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_SOCKET_REBIND socketId=$socketId port=$port reason=$reason " +
                "boundNetworkId=$boundNetworkId transportInstanceId=$transportInstanceId"
        )
    }

    fun socketBind(socketId: Long, port: Int, boundNetworkId: String, transportInstanceId: Long) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_SOCKET_BIND socketId=$socketId port=$port " +
                "boundNetworkId=$boundNetworkId transportInstanceId=$transportInstanceId"
        )
    }

    /** Observe-only: socket bound with resolved local address (BOUND fact). */
    fun socketBound(
        socketId: Long,
        networkId: String,
        localAddress: String,
        boundNetworkId: String,
        rebindGeneration: Long
    ) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_SOCKET_BOUND socketId=$socketId networkId=$networkId " +
                "localAddress=$localAddress boundNetworkId=$boundNetworkId rebindGeneration=$rebindGeneration"
        )
    }

    /** Observe-only: transport manager marked receive loop active for this socket. */
    fun receiveLoopStarted(socketId: Long, threadId: Long, networkId: String, boundNetworkId: String) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_RECEIVE_LOOP_STARTED socketId=$socketId threadId=$threadId " +
                "networkId=$networkId boundNetworkId=$boundNetworkId"
        )
    }

    fun receiveLoopStopped(socketId: Long, reason: String) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_RECEIVE_LOOP_STOPPED socketId=$socketId reason=$reason"
        )
    }

    /**
     * Observe-only: receive thread is blocking on [socketId].
     * [lastInboundElapsedMs] is time since last inbound on this socket (or since bind if none yet).
     */
    fun receiveLoopBlocking(socketId: Long, lastInboundElapsedMs: Long, networkId: String) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_RECEIVE_LOOP_BLOCKING socketId=$socketId " +
                "lastInboundElapsedMs=$lastInboundElapsedMs networkId=$networkId"
        )
    }

    /** Observe-only: first inbound datagram after a socket rebind. */
    fun firstInboundAfterRebind(
        socketId: Long,
        networkId: String,
        sourceAddress: String,
        deltaFromRebindMs: Long,
        rebindGeneration: Long
    ) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_FIRST_INBOUND_AFTER_REBIND socketId=$socketId " +
                "networkId=$networkId sourceAddress=$sourceAddress deltaFromRebindMs=$deltaFromRebindMs " +
                "rebindGeneration=$rebindGeneration"
        )
    }

    /** Observe-only: first outbound datagram after a socket rebind. */
    fun firstOutboundAfterRebind(
        socketId: Long,
        networkId: String,
        remote: String,
        deltaFromRebindMs: Long,
        rebindGeneration: Long
    ) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_FIRST_OUTBOUND_AFTER_REBIND socketId=$socketId " +
                "networkId=$networkId remote=$remote deltaFromRebindMs=$deltaFromRebindMs " +
                "rebindGeneration=$rebindGeneration"
        )
    }

    /** Observe-only: both directions observed after rebind (link qualification v1). */
    fun bidirectionalConfirmed(
        socketId: Long,
        networkId: String,
        hasOutboundAfterRebind: Boolean,
        hasInboundAfterRebind: Boolean,
        deltaFromRebindMs: Long,
        rebindGeneration: Long
    ) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_BIDIRECTIONAL_CONFIRMED socketId=$socketId " +
                "networkId=$networkId hasOutboundAfterRebind=$hasOutboundAfterRebind " +
                "hasInboundAfterRebind=$hasInboundAfterRebind deltaFromRebindMs=$deltaFromRebindMs " +
                "rebindGeneration=$rebindGeneration"
        )
    }

    fun transportStateChanged(oldState: String, newState: String, transportInstanceId: Long, detail: String) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_TRANSPORT_STATE_CHANGED oldState=$oldState newState=$newState " +
                "transportInstanceId=$transportInstanceId $detail"
        )
    }

    fun transportReady(networkId: String, socketId: Long, boundNetworkId: String, transportInstanceId: Long) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_TRANSPORT_READY networkId=$networkId socketId=$socketId " +
                "boundNetworkId=$boundNetworkId transportInstanceId=$transportInstanceId"
        )
    }

    fun datagramReceived(
        signalType: String,
        socketId: Long,
        localIp: String,
        srcIp: String,
        srcPort: Int,
        networkId: String,
        bytes: Int
    ) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_DATAGRAM_RECEIVED socketId=$socketId localIp=$localIp " +
                "srcIp=$srcIp srcPort=$srcPort signalType=$signalType bytes=$bytes networkId=$networkId"
        )
    }

    fun datagramWriteAccepted(type: String, dst: String, bytes: Int, socketId: Long, networkId: String) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_DATAGRAM_WRITE_ACCEPTED type=$type dst=$dst bytes=$bytes " +
                "socketId=$socketId networkId=$networkId"
        )
    }

    fun datagramSent(
        signalType: String,
        socketId: Long,
        localIp: String,
        dstIp: String,
        dstPort: Int,
        networkId: String,
        bytes: Int,
        sessionId: String? = null,
        nonce: String? = null
    ) {
        val sessionPart = sessionId?.let { " sessionId=$it" }.orEmpty()
        val noncePart = nonce?.let { " nonce=$it" }.orEmpty()
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_DATAGRAM_SENT socketId=$socketId localIp=$localIp dstIp=$dstIp " +
                "dstPort=$dstPort signalType=$signalType bytes=$bytes networkId=$networkId$sessionPart$noncePart"
        )
    }

    /** Observe-only: inbound resumed after prolonged silence from a peer IP. */
    fun inboundResumed(srcIp: String, srcPort: Int, silenceMs: Long) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_INBOUND_RESUMED srcIp=$srcIp srcPort=$srcPort silenceMs=$silenceMs"
        )
    }

    /**
     * Observe-only: outbound accepted while no recent inbound from the same peer IP.
     * Typical of post-rebind path asymmetry (SENT without matching RECEIVED).
     */
    fun pathAsymmetryObserved(
        dstIp: String,
        dstPort: Int,
        lastInboundAgeMs: Long,
        outboundSinceLastInbound: Int,
        signalType: String
    ) {
        TalkbackLog.i(
            "TransportCapabilityTrace SIGNAL_PATH_ASYMMETRY dstIp=$dstIp dstPort=$dstPort " +
                "lastInboundAgeMs=$lastInboundAgeMs outboundSinceLastInbound=$outboundSinceLastInbound " +
                "signalType=$signalType"
        )
    }
}