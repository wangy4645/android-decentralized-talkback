package com.talkback.core.session

/**
 * Observability-only snapshot for conference transmit barrier decisions (ADR-0026).
 * Peer recovery fields are telemetry; gating uses [ConferenceBarrierPolicy.EDGE_SCOPED].
 */
enum class ConferenceBarrierPolicy {
    /** Deprecated: remote recovery blocked all capture (pre ADR-0026). */
    @Deprecated("Superseded by EDGE_SCOPED per ADR-0026")
    CONFERENCE_WIDE,
    /** Local publish preconditions only; remote recovery is diagnostic. */
    EDGE_SCOPED
}

enum class ConferenceBarrierMediaState {
    CONNECTED,
    RECOVERING,
    FAILED,
    UNKNOWN
}

data class ConferenceBarrierPeerReason(
    val peer: String,
    val recovering: Boolean,
    val obligationOpen: Boolean,
    val failedMediaRecovery: Boolean,
    val iceConnected: Boolean,
    val mediaState: ConferenceBarrierMediaState
)

data class ConferenceBarrierSnapshot(
    val sessionId: String,
    val barrierPolicy: ConferenceBarrierPolicy,
    val canPublish: Boolean,
    val blockedReasons: List<ConferenceBarrierPeerReason>,
    val joinedPeers: Set<String>,
    val connectedPeers: Set<String>,
    val recoveringPeers: Set<String>,
    val obligationOpenPeers: Set<String>,
    val failedPeers: Set<String>,
    val anyRecovering: Boolean
) {
    fun toLogLine(): String = buildString {
        append("session=").append(sessionId)
        append(" policy=").append(barrierPolicy)
        append(" canPublish=").append(canPublish)
        append(" anyRecovering=").append(anyRecovering)
        append(" joined=").append(joinedPeers.sorted().joinToString(","))
        append(" connected=").append(connectedPeers.sorted().joinToString(","))
        append(" recovering=").append(recoveringPeers.sorted().joinToString(","))
        append(" obligationOpen=").append(obligationOpenPeers.sorted().joinToString(","))
        append(" failed=").append(failedPeers.sorted().joinToString(","))
        if (blockedReasons.isNotEmpty()) {
            append(" blocked=[")
            append(
                blockedReasons.joinToString(";") { reason ->
                    "${reason.peer}{recovering=${reason.recovering}," +
                        "obligationOpen=${reason.obligationOpen}," +
                        "failed=${reason.failedMediaRecovery}," +
                        "ice=${reason.iceConnected}," +
                        "media=${reason.mediaState}}"
                }
            )
            append(']')
        }
    }
}

object ConferenceBarrierDiagnostics {

    fun snapshot(
        sessionId: String,
        joinedPeers: Set<String>,
        connectedPeers: Set<String>,
        controller: ConferenceEdgeRecoveryController,
        isIceConnected: (String) -> Boolean,
        gateInput: ConferenceMediaTransmitGate.Input,
        policy: ConferenceBarrierPolicy = ConferenceBarrierPolicy.EDGE_SCOPED
    ): ConferenceBarrierSnapshot {
        val facts = controller.factsForSession(sessionId)
        val recoveringPeers = facts.recoveringRemoteModuleIds
        val failedPeers = facts.failedRemoteModuleIds
        val obligationOpenPeers = joinedPeers.filter { peer ->
            controller.edgeObligationOpen(sessionId, peer)
        }.toSet()
        val peerReasons = joinedPeers.map { peer ->
            val recovering = controller.isEdgeRecovering(sessionId, peer)
            val obligationOpen = controller.edgeObligationOpen(sessionId, peer)
            val failed = peer in failedPeers
            val iceConnected = isIceConnected(peer)
            ConferenceBarrierPeerReason(
                peer = peer,
                recovering = recovering,
                obligationOpen = obligationOpen,
                failedMediaRecovery = failed,
                iceConnected = iceConnected,
                mediaState = resolveMediaState(
                    recovering = recovering,
                    failedMediaRecovery = failed,
                    iceConnected = iceConnected
                )
            )
        }
        val canPublish = ConferenceMediaTransmitGate.canPublishConferenceAudio(gateInput)
        val blockedReasons = if (!canPublish && !gateInput.localPublisherReady) {
            peerReasons.filter { !it.iceConnected }
        } else {
            emptyList()
        }
        return ConferenceBarrierSnapshot(
            sessionId = sessionId,
            barrierPolicy = policy,
            canPublish = canPublish,
            blockedReasons = blockedReasons,
            joinedPeers = joinedPeers,
            connectedPeers = connectedPeers,
            recoveringPeers = recoveringPeers,
            obligationOpenPeers = obligationOpenPeers,
            failedPeers = failedPeers,
            anyRecovering = facts.anyRecovering
        )
    }

    fun formatLog(action: String, snapshot: ConferenceBarrierSnapshot): String =
        "CONFERENCE_BARRIER_SNAPSHOT action=$action ${snapshot.toLogLine()}"

    private fun resolveMediaState(
        recovering: Boolean,
        failedMediaRecovery: Boolean,
        iceConnected: Boolean
    ): ConferenceBarrierMediaState {
        if (recovering) return ConferenceBarrierMediaState.RECOVERING
        if (failedMediaRecovery) return ConferenceBarrierMediaState.FAILED
        if (iceConnected) return ConferenceBarrierMediaState.CONNECTED
        return ConferenceBarrierMediaState.UNKNOWN
    }
}