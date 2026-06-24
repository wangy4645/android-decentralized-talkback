package com.talkback.core.qos

data class QosSnapshot(
    val remoteModuleId: String,
    val rttMs: Long = -1,
    val packetLossPercent: Double = -1.0,
    val jitterMs: Long = -1,
    val iceState: String = "UNKNOWN",
    val updatedMs: Long = System.currentTimeMillis()
)

/**
 * Collects network quality per mesh module and per active unicast session.
 */
class NetworkQualityMonitor {
    private val groupSnapshots = mutableMapOf<String, QosSnapshot>()
    private val unicastSnapshots = mutableMapOf<String, QosSnapshot>()

    @Synchronized
    fun updateGroupIceState(remoteModuleId: String, iceState: String) {
        val prev = groupSnapshots[remoteModuleId]
        groupSnapshots[remoteModuleId] = (prev ?: QosSnapshot(remoteModuleId)).copy(
            iceState = iceState,
            updatedMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun updateUnicastIceState(sessionId: String, remoteModuleId: String, iceState: String) {
        val prev = unicastSnapshots[sessionId]
        unicastSnapshots[sessionId] = (prev ?: QosSnapshot(remoteModuleId)).copy(
            remoteModuleId = remoteModuleId,
            iceState = iceState,
            updatedMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun updateGroupStats(remoteModuleId: String, rttMs: Long, lossPercent: Double, jitterMs: Long) {
        groupSnapshots[remoteModuleId] = QosSnapshot(
            remoteModuleId = remoteModuleId,
            rttMs = rttMs,
            packetLossPercent = lossPercent,
            jitterMs = jitterMs,
            iceState = groupSnapshots[remoteModuleId]?.iceState ?: "UNKNOWN"
        )
    }

    @Synchronized
    fun resetGroup(remoteModuleId: String) {
        groupSnapshots.remove(remoteModuleId)
    }

    @Synchronized
    fun resetUnicast(sessionId: String) {
        unicastSnapshots.remove(sessionId)
    }

    @Synchronized
    fun snapshotGroup(remoteModuleId: String): QosSnapshot? = groupSnapshots[remoteModuleId]

    @Synchronized
    fun snapshotUnicast(sessionId: String): QosSnapshot? = unicastSnapshots[sessionId]

    /** Mesh QoS keyed by remote module (legacy API). */
    @Synchronized
    fun snapshot(remoteModuleId: String): QosSnapshot? = groupSnapshots[remoteModuleId]

    @Synchronized
    fun updateIceState(remoteModuleId: String, iceState: String) {
        updateGroupIceState(remoteModuleId, iceState)
    }

    @Synchronized
    fun updateStats(remoteModuleId: String, rttMs: Long, lossPercent: Double, jitterMs: Long) {
        updateGroupStats(remoteModuleId, rttMs, lossPercent, jitterMs)
    }

    @Synchronized
    fun resetRemote(remoteModuleId: String) {
        resetGroup(remoteModuleId)
    }

    @Synchronized
    fun all(): List<QosSnapshot> = (groupSnapshots.values + unicastSnapshots.values).toList()

    @Synchronized
    fun formatSummary(): String {
        val merged = all()
        if (merged.isEmpty()) return "QoS: n/a"
        return merged.joinToString(" | ") {
            "[$it.remoteModuleId rtt=${it.rttMs}ms loss=${it.packetLossPercent}% ice=${it.iceState}]"
        }
    }

    @Synchronized
    fun isGroupConnected(remoteModuleId: String): Boolean =
        IceConnectivity.isConnected(groupSnapshots[remoteModuleId]?.iceState)

    @Synchronized
    fun isUnicastConnected(sessionId: String): Boolean =
        IceConnectivity.isConnected(unicastSnapshots[sessionId]?.iceState)
}
