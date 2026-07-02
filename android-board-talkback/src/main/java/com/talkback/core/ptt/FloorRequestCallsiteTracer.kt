package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.transport.TransportBinding
import com.talkback.core.util.TalkbackLog

/**
 * Permanent instrumentation for floor-request dispatch forensics.
 * Read-only: never mutates transport, session, or roster state.
 *
 * Grep: FLOOR_REQUEST_CALLSITE
 */
object FloorRequestCallsiteTracer {

    enum class Stage {
        RESOLVE,
        SEND_INTENT,
        SEND_SIGNAL,
        UDP_WRITE,
        LOCAL_AUTHORITY
    }

    private var logSink: ((String) -> Unit)? = null

    fun resetForTests(sink: ((String) -> Unit)? = {}) {
        logSink = sink
    }

    fun callStackHash(skipFrames: Int = 3, frameCount: Int = 6): String {
        val frames = Thread.currentThread().stackTrace
        val digest = frames
            .drop(skipFrames)
            .take(frameCount)
            .joinToString(">") { "${it.fileName}:${it.methodName}:${it.lineNumber}" }
        val hash = digest.hashCode()
        return (if (hash < 0) -hash else hash).toString(16)
    }

    fun recordResolve(
        sessionId: String,
        localModuleId: String,
        coordinatorHash: Int,
        sessionHash: Int,
        authorityModuleId: String?,
        route: FloorAuthorityRouteResult,
        transport: TransportBinding?
    ) {
        when (route) {
            is FloorAuthorityRouteResult.Ok -> {
                val d = route.decision
                emit(
                    stage = Stage.RESOLVE,
                    sessionId = sessionId,
                    localModuleId = localModuleId,
                    coordinatorHash = coordinatorHash,
                    sessionHash = sessionHash,
                    authorityModuleId = authorityModuleId ?: d.authorityModuleId.value,
                    resolvedPeerHost = d.signalPeer.host,
                    resolvedPeerPort = d.signalPeer.port,
                    envelopeToModuleId = d.authorityEndpoint.moduleId.value,
                    resolvedFrom = d.resolvedFrom,
                    transportBindingEpoch = transport?.epoch ?: d.authorityEpoch,
                    transportBindingSource = transport?.source?.name,
                    sendResult = "ROUTE_OK",
                    routeResult = "OK peerSetHash=${d.peerSetHash}"
                )
            }
            is FloorAuthorityRouteResult.Invalid -> {
                emit(
                    stage = Stage.RESOLVE,
                    sessionId = sessionId,
                    localModuleId = localModuleId,
                    coordinatorHash = coordinatorHash,
                    sessionHash = sessionHash,
                    authorityModuleId = authorityModuleId ?: route.authorityModuleId?.value,
                    resolvedPeerHost = transport?.host,
                    resolvedPeerPort = transport?.port,
                    envelopeToModuleId = null,
                    resolvedFrom = FloorRouteDecision.RESOLVED_FROM,
                    transportBindingEpoch = transport?.epoch,
                    transportBindingSource = transport?.source?.name,
                    sendResult = "ROUTE_INVALID",
                    routeResult = route.reason.name
                )
            }
        }
    }

    fun recordSendIntent(
        sessionId: String,
        localModuleId: String,
        coordinatorHash: Int,
        sessionHash: Int,
        authorityModuleId: String?,
        sendTarget: PeerTarget?,
        envelopeTo: EndpointAddress?,
        resolvedFrom: String?,
        transportBindingEpoch: Long?,
        transportBindingSource: String?,
        sendResult: String
    ) {
        emit(
            stage = Stage.SEND_INTENT,
            sessionId = sessionId,
            localModuleId = localModuleId,
            coordinatorHash = coordinatorHash,
            sessionHash = sessionHash,
            authorityModuleId = authorityModuleId,
            resolvedPeerHost = sendTarget?.host,
            resolvedPeerPort = sendTarget?.port,
            envelopeToModuleId = envelopeTo?.moduleId?.value,
            resolvedFrom = resolvedFrom,
            transportBindingEpoch = transportBindingEpoch,
            transportBindingSource = transportBindingSource,
            sendResult = sendResult,
            routeResult = null
        )
    }

    fun recordSendSignal(
        sessionId: String,
        localModuleId: String,
        coordinatorHash: Int,
        sessionHash: Int?,
        sendTarget: PeerTarget,
        envelope: SignalEnvelope,
        sendResult: String
    ) {
        if (envelope.type != SignalType.FLOOR_REQUEST) return
        emit(
            stage = Stage.SEND_SIGNAL,
            sessionId = sessionId,
            localModuleId = localModuleId,
            coordinatorHash = coordinatorHash,
            sessionHash = sessionHash ?: 0,
            authorityModuleId = envelope.to?.moduleId?.value,
            resolvedPeerHost = sendTarget.host,
            resolvedPeerPort = sendTarget.port,
            envelopeToModuleId = envelope.to?.moduleId?.value,
            resolvedFrom = null,
            transportBindingEpoch = null,
            transportBindingSource = null,
            sendResult = sendResult,
            routeResult = "signalType=${envelope.type.name}"
        )
    }

    fun recordUdpWrite(
        sendTarget: PeerTarget,
        envelope: SignalEnvelope,
        sendResult: String
    ) {
        if (envelope.type != SignalType.FLOOR_REQUEST) return
        emit(
            stage = Stage.UDP_WRITE,
            sessionId = envelope.sessionId,
            localModuleId = envelope.from.moduleId.value,
            coordinatorHash = 0,
            sessionHash = 0,
            authorityModuleId = envelope.to?.moduleId?.value,
            resolvedPeerHost = sendTarget.host,
            resolvedPeerPort = sendTarget.port,
            envelopeToModuleId = envelope.to?.moduleId?.value,
            resolvedFrom = null,
            transportBindingEpoch = null,
            transportBindingSource = null,
            sendResult = sendResult,
            routeResult = "from=${envelope.from.key}"
        )
    }

    fun recordLocalAuthority(
        sessionId: String,
        localModuleId: String,
        coordinatorHash: Int,
        sessionHash: Int,
        authorityModuleId: String?
    ) {
        emit(
            stage = Stage.LOCAL_AUTHORITY,
            sessionId = sessionId,
            localModuleId = localModuleId,
            coordinatorHash = coordinatorHash,
            sessionHash = sessionHash,
            authorityModuleId = authorityModuleId,
            resolvedPeerHost = null,
            resolvedPeerPort = null,
            envelopeToModuleId = localModuleId,
            resolvedFrom = "LOCAL_AUTHORITY",
            transportBindingEpoch = null,
            transportBindingSource = null,
            sendResult = "SEND_OK_LOCAL",
            routeResult = null
        )
    }

    private fun emit(
        stage: Stage,
        sessionId: String,
        localModuleId: String,
        coordinatorHash: Int,
        sessionHash: Int,
        authorityModuleId: String?,
        resolvedPeerHost: String?,
        resolvedPeerPort: Int?,
        envelopeToModuleId: String?,
        resolvedFrom: String?,
        transportBindingEpoch: Long?,
        transportBindingSource: String?,
        sendResult: String,
        routeResult: String?
    ) {
        val threadId = Thread.currentThread().id
        val stackHash = callStackHash()
        val peer = when {
            resolvedPeerHost == null -> "null"
            resolvedPeerPort == null -> resolvedPeerHost
            else -> "$resolvedPeerHost:$resolvedPeerPort"
        }
        val line = buildString {
            append("FLOOR_REQUEST_CALLSITE ")
            append("stage=$stage ")
            append("sid=$sessionId ")
            append("local=$localModuleId ")
            append("coordinatorHash=$coordinatorHash ")
            append("sessionHash=$sessionHash ")
            append("threadId=$threadId ")
            append("callStackHash=$stackHash ")
            append("authority=${authorityModuleId ?: "null"} ")
            append("resolvedPeer=$peer ")
            append("sendTarget=$peer ")
            append("envelopeTo=${envelopeToModuleId ?: "null"} ")
            append("resolvedFrom=${resolvedFrom ?: "n/a"} ")
            append("transportEpoch=${transportBindingEpoch ?: "n/a"} ")
            append("transportSource=${transportBindingSource ?: "n/a"} ")
            append("sendResult=$sendResult")
            if (routeResult != null) {
                append(" routeResult=$routeResult")
            }
        }
        (logSink ?: { TalkbackLog.i(it) })(line)
    }
}
