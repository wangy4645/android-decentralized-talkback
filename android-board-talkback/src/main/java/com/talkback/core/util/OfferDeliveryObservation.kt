package com.talkback.core.util

import com.talkback.core.model.ConferenceJoinIntent
import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType

/**
 * ICE Restart Signal Path Classification — observation only.
 *
 * Recovery Reattach Ingress Trace (R1/R2/R3) on peer:
 *
 * ```
 * UDP_DATAGRAM_RECEIVED          (raw socket accept; before decode)
 *         |
 *         v
 * SIGNAL_ENVELOPE_DECODED        (JSON -> SignalEnvelope)
 *         |
 *         v
 * RECOVERY_REATTACH_CLASSIFIED   (joinIntent=RECOVERY_REATTACH only)
 *         |
 *         v
 * REMOTE_RECEIVE / RECOVERY_HANDLER_ENTER / HANDLER_ACCEPT
 * ```
 *
 * Path-diff: GROUP_MESH vs RECOVERY_REATTACH may share SignalType.GROUP_JOIN
 * but differ in joinIntent / sessionId / lineage — SignalPathKey must include
 * signalDomain (pathKind), not edge alone.
 *
 * Grep: OFFER_DELIVERY
 */
object OfferDeliveryObservation {

    enum class Stage {
        SEND_REQUEST,
        LOCAL_ACCEPT,
        /** Raw UDP accept — Case R1 if absent for a sent L*. */
        UDP_DATAGRAM_RECEIVED,
        /** Envelope decode OK — Case R2 if UDP yes but this absent. */
        SIGNAL_ENVELOPE_DECODED,
        MESSAGE_TYPE_CLASSIFIED,
        /** Recovery domain only — Case R3 if decode yes but this absent. */
        RECOVERY_REATTACH_CLASSIFIED,
        REMOTE_RECEIVE,
        RECOVERY_HANDLER_ENTER,
        HANDLER_ACCEPT,
        ANSWER_RETURN
    }

    enum class PathKind {
        GROUP_MESH,
        RECOVERY_REATTACH,
        OTHER
    }

    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        logSink = sink
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }

    fun emit(
        stage: Stage,
        remoteModuleId: String,
        offerLineageId: String? = null,
        sessionId: String? = null,
        restartAttemptId: Long? = null,
        transportGeneration: Long? = null,
        decision: String? = null,
        detail: String? = null,
        pathKind: PathKind? = null,
        signalType: String? = null,
        joinIntent: String? = null
    ) {
        val sb = StringBuilder("OFFER_DELIVERY stage=").append(stage.name)
        sb.append(" remote=").append(remoteModuleId)
        sb.append(" offerLineageId=").append(offerLineageId?.takeIf { it.isNotBlank() } ?: "NONE")
        // signalDomain == pathKind; analyzer SignalPathKey MUST include this.
        pathKind?.let {
            sb.append(" pathKind=").append(it.name)
            sb.append(" signalDomain=").append(it.name)
        }
        signalType?.let { sb.append(" signalType=").append(it) }
        joinIntent?.let { sb.append(" joinIntent=").append(it) }
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        restartAttemptId?.let { sb.append(" restartAttemptId=").append(it) }
        transportGeneration?.let { sb.append(" gen=").append(it) }
        decision?.let { sb.append(" decision=").append(it) }
        detail?.let { sb.append(" detail=").append(it) }
        log(sb.toString())
    }

    /** Peer: raw DatagramSocket.receive success, before decode. */
    fun udpDatagramReceived(srcHost: String, srcPort: Int, socketId: Long, bytes: Int) {
        emit(
            stage = Stage.UDP_DATAGRAM_RECEIVED,
            remoteModuleId = "UNKNOWN",
            detail = "src=$srcHost:$srcPort socketId=$socketId bytes=$bytes"
        )
    }

    /** Peer: JSON envelope decode success (any SignalType). */
    fun signalEnvelopeDecoded(
        envelope: SignalEnvelope,
        srcHost: String,
        srcPort: Int,
        socketId: Long
    ) {
        val correlation = correlationFromEnvelope(envelope)
        val lineage = correlation.offerLineageId
        val attempt = correlation.restartAttemptId
        val gen = correlation.transportGeneration
        val payload = if (envelope.type == SignalType.GROUP_JOIN) {
            GroupSessionPayload.decode(envelope.payload)
        } else {
            null
        }
        emit(
            stage = Stage.SIGNAL_ENVELOPE_DECODED,
            remoteModuleId = envelope.from.moduleId.value,
            offerLineageId = lineage,
            sessionId = envelope.sessionId,
            restartAttemptId = attempt,
            transportGeneration = gen,
            pathKind = pathKindOf(envelope),
            signalType = envelope.type.name,
            joinIntent = payload?.joinIntent?.name,
            detail = "src=$srcHost:$srcPort socketId=$socketId"
        )
    }

    fun pathKindOf(envelope: SignalEnvelope): PathKind {
        if (envelope.type != SignalType.GROUP_JOIN) return PathKind.OTHER
        val intent = GroupSessionPayload.decode(envelope.payload)?.joinIntent
            ?: return PathKind.GROUP_MESH
        return when (intent) {
            ConferenceJoinIntent.RECOVERY_REATTACH -> PathKind.RECOVERY_REATTACH
            else -> PathKind.GROUP_MESH
        }
    }

    data class EnvelopeCorrelation(
        val offerLineageId: String?,
        val restartAttemptId: Long?,
        val transportGeneration: Long?,
        val deliveryAttemptId: Long?
    )

    fun correlationFromEnvelope(envelope: SignalEnvelope): EnvelopeCorrelation {
        if (envelope.type != SignalType.GROUP_JOIN) {
            return EnvelopeCorrelation(null, null, null, null)
        }
        val payload = GroupSessionPayload.decode(envelope.payload)
            ?: return EnvelopeCorrelation(null, null, null, null)
        return EnvelopeCorrelation(
            offerLineageId = payload.offerLineageId,
            restartAttemptId = payload.restartAttemptId,
            transportGeneration = payload.transportGeneration,
            deliveryAttemptId = payload.deliveryAttemptId
        )
    }

    /** Peer ingress: after datagram decode, before handler. */
    fun classifyInbound(
        envelope: SignalEnvelope,
        srcHost: String,
        srcPort: Int,
        socketId: Long,
        localModuleId: String? = null
    ) {
        val correlation = correlationFromEnvelope(envelope)
        val lineage = correlation.offerLineageId
        val attempt = correlation.restartAttemptId
        val gen = correlation.transportGeneration
        val payload = if (envelope.type == SignalType.GROUP_JOIN) {
            GroupSessionPayload.decode(envelope.payload)
        } else {
            null
        }
        val pathKind = pathKindOf(envelope)
        emit(
            stage = Stage.MESSAGE_TYPE_CLASSIFIED,
            remoteModuleId = envelope.from.moduleId.value,
            offerLineageId = lineage,
            sessionId = envelope.sessionId,
            restartAttemptId = attempt,
            transportGeneration = gen,
            pathKind = pathKind,
            signalType = envelope.type.name,
            joinIntent = payload?.joinIntent?.name,
            detail = "src=$srcHost:$srcPort socketId=$socketId"
        )
        if (pathKind == PathKind.RECOVERY_REATTACH) {
            emit(
                stage = Stage.RECOVERY_REATTACH_CLASSIFIED,
                remoteModuleId = envelope.from.moduleId.value,
                offerLineageId = lineage,
                sessionId = envelope.sessionId,
                restartAttemptId = attempt,
                transportGeneration = gen,
                pathKind = pathKind,
                signalType = envelope.type.name,
                joinIntent = payload?.joinIntent?.name,
                detail = "src=$srcHost:$srcPort socketId=$socketId"
            )
        }
    }

    /**
     * Peer ingress ladder REMOTE_RECEIVE for GROUP_JOIN (observation + ingress fact producer).
     */
    fun emitRemoteReceive(
        envelope: SignalEnvelope,
        localModuleId: String,
        detail: String
    ) {
        val correlation = correlationFromEnvelope(envelope)
        emit(
            stage = Stage.REMOTE_RECEIVE,
            remoteModuleId = envelope.from.moduleId.value,
            pathKind = pathKindOf(envelope),
            signalType = envelope.type.name,
            offerLineageId = correlation.offerLineageId,
            sessionId = envelope.sessionId,
            restartAttemptId = correlation.restartAttemptId,
            transportGeneration = correlation.transportGeneration,
            detail = detail
        )
        RecoveryIngressObservation.onRemoteIngressReceive(
            envelope = envelope,
            localModuleId = localModuleId,
            sessionId = envelope.sessionId
        )
    }
}
