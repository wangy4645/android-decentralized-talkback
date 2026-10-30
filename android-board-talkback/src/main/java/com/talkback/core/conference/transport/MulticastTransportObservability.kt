package com.talkback.core.conference.transport

/**
 * Phase 1 receive-side observability (diagnostic only — not a gate).
 */
data class MulticastTransportObservabilitySnapshot(
    val packetsSent: Long,
    val packetsReceived: Long,
    val ingressAccepted: Long,
    val ingressRejected: Long,
    val sendErrors: Long,
    val receiveErrors: Long,
    val lastRxWallMs: Long,
    val lastSendDurationUs: Long,
    val lastReceiveDurationUs: Long,
    val lastSendError: String?,
    val lastReceiveError: String?,
    val perSourcePacketsReceived: Map<String, Long>,
    val perSourceLastRxWallMs: Map<String, Long>,
    val perSourceJitterDepth: Map<String, Int>,
    val decodeDurationUs: Long,
    val mixDurationUs: Long,
    val audioTrackUnderrunCount: Long,
    val playoutBudgetUsedUs: Long,
)

class MulticastTransportObservability {
    var packetsSent: Long = 0
        private set
    var packetsReceived: Long = 0
        private set
    var ingressAccepted: Long = 0
        private set
    var ingressRejected: Long = 0
        private set
    var sendErrors: Long = 0
        private set
    var receiveErrors: Long = 0
        private set
    var lastRxWallMs: Long = 0
        private set
    var lastSendDurationUs: Long = 0
        private set
    var lastReceiveDurationUs: Long = 0
        private set
    var lastSendError: String? = null
        private set
    var lastReceiveError: String? = null
        private set
    var decodeDurationUs: Long = 0
        private set
    var mixDurationUs: Long = 0
        private set
    var audioTrackUnderrunCount: Long = 0
        private set
    var playoutBudgetUsedUs: Long = 0
        private set

    private val perSourcePacketsReceived = linkedMapOf<String, Long>()
    private val perSourceLastRxWallMs = linkedMapOf<String, Long>()
    private val perSourceJitterDepth = linkedMapOf<String, Int>()

    fun recordSend(durationUs: Long) {
        packetsSent += 1
        lastSendDurationUs = durationUs
    }

    fun recordSendError(detail: String? = null) {
        sendErrors += 1
        if (detail != null) lastSendError = detail
    }

    fun recordReceive(
        sourceIdentity: String,
        rxWallMs: Long,
        durationUs: Long,
        jitterDepth: Int? = null,
    ) {
        packetsReceived += 1
        lastRxWallMs = rxWallMs
        lastReceiveDurationUs = durationUs
        perSourcePacketsReceived[sourceIdentity] =
            (perSourcePacketsReceived[sourceIdentity] ?: 0L) + 1L
        perSourceLastRxWallMs[sourceIdentity] = rxWallMs
        if (jitterDepth != null) {
            perSourceJitterDepth[sourceIdentity] = jitterDepth
        }
    }

    fun recordIngressAccepted(
        sourceIdentity: String,
        rxWallMs: Long,
        durationUs: Long,
        jitterDepth: Int? = null,
    ) {
        ingressAccepted += 1
        recordReceive(sourceIdentity, rxWallMs, durationUs, jitterDepth)
    }

    fun recordIngressRejected() {
        ingressRejected += 1
    }

    fun recordReceiveError(detail: String? = null) {
        receiveErrors += 1
        if (detail != null) lastReceiveError = detail
    }

    fun recordDecodeMixPlayout(
        decodeUs: Long,
        mixUs: Long,
        playoutBudgetUsedUs: Long,
        underrun: Boolean,
    ) {
        decodeDurationUs = decodeUs
        mixDurationUs = mixUs
        this.playoutBudgetUsedUs = playoutBudgetUsedUs
        if (underrun) {
            audioTrackUnderrunCount += 1
        }
    }

    fun snapshot(): MulticastTransportObservabilitySnapshot =
        MulticastTransportObservabilitySnapshot(
            packetsSent = packetsSent,
            packetsReceived = packetsReceived,
            ingressAccepted = ingressAccepted,
            ingressRejected = ingressRejected,
            sendErrors = sendErrors,
            receiveErrors = receiveErrors,
            lastRxWallMs = lastRxWallMs,
            lastSendDurationUs = lastSendDurationUs,
            lastReceiveDurationUs = lastReceiveDurationUs,
            lastSendError = lastSendError,
            lastReceiveError = lastReceiveError,
            perSourcePacketsReceived = perSourcePacketsReceived.toMap(),
            perSourceLastRxWallMs = perSourceLastRxWallMs.toMap(),
            perSourceJitterDepth = perSourceJitterDepth.toMap(),
            decodeDurationUs = decodeDurationUs,
            mixDurationUs = mixDurationUs,
            audioTrackUnderrunCount = audioTrackUnderrunCount,
            playoutBudgetUsedUs = playoutBudgetUsedUs,
        )
}
