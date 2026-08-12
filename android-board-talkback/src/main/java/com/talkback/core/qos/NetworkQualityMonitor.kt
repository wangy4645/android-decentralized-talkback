package com.talkback.core.qos

import com.talkback.core.webrtc.MediaBearerScope

data class QosSnapshot(
    val remoteModuleId: String,
    val rttMs: Long = -1,
    val packetLossPercent: Double = -1.0,
    val jitterMs: Long = -1,
    val iceState: String = "UNKNOWN",
    val updatedMs: Long = System.currentTimeMillis()
)

/**
 * Collects network quality per mesh scope (GROUP / CONFERENCE) and per unicast session.
 * ADR-0052: GROUP and CONFERENCE ICE states are stored independently.
 */
class NetworkQualityMonitor {
    private val meshStore = ScopedQosStore()
    private val unicastSnapshots = mutableMapOf<String, QosSnapshot>()

    @Synchronized
    fun updateIceState(scope: MediaBearerScope, key: String, iceState: String) {
        when (scope) {
            MediaBearerScope.GROUP, MediaBearerScope.CONFERENCE ->
                meshStore.updateIceState(scope, key, iceState)
            MediaBearerScope.UNICAST ->
                error("Use updateUnicastIceState for UNICAST scope")
        }
    }

    @Synchronized
    fun updateGroupIceState(remoteModuleId: String, iceState: String) {
        updateIceState(MediaBearerScope.GROUP, remoteModuleId, iceState)
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
        meshStore.updateGroupStats(remoteModuleId, rttMs, lossPercent, jitterMs)
    }

    @Synchronized
    fun resetMesh(scope: MediaBearerScope, remoteModuleId: String) {
        when (scope) {
            MediaBearerScope.GROUP, MediaBearerScope.CONFERENCE ->
                meshStore.reset(scope, remoteModuleId)
            MediaBearerScope.UNICAST ->
                error("Use resetUnicast for UNICAST scope")
        }
    }

    @Synchronized
    fun resetGroup(remoteModuleId: String) {
        resetMesh(MediaBearerScope.GROUP, remoteModuleId)
    }

    @Synchronized
    fun resetConference(remoteModuleId: String) {
        resetMesh(MediaBearerScope.CONFERENCE, remoteModuleId)
    }

    @Synchronized
    fun resetUnicast(sessionId: String) {
        unicastSnapshots.remove(sessionId)
    }

    @Synchronized
    fun snapshot(scope: MediaBearerScope, key: String): QosSnapshot? = when (scope) {
        MediaBearerScope.GROUP, MediaBearerScope.CONFERENCE -> meshStore.snapshot(scope, key)
        MediaBearerScope.UNICAST -> unicastSnapshots[key]
    }

    @Synchronized
    fun snapshotGroup(remoteModuleId: String): QosSnapshot? =
        snapshot(MediaBearerScope.GROUP, remoteModuleId)

    @Synchronized
    fun snapshotConference(remoteModuleId: String): QosSnapshot? =
        snapshot(MediaBearerScope.CONFERENCE, remoteModuleId)

    @Synchronized
    fun snapshotUnicast(sessionId: String): QosSnapshot? = unicastSnapshots[sessionId]

    @Deprecated(
        message = "Media scope required — use snapshot(scope, key)",
        level = DeprecationLevel.ERROR
    )
    @Synchronized
    fun snapshot(remoteModuleId: String): QosSnapshot? =
        error("Media scope required: use snapshot(MediaBearerScope.GROUP or CONFERENCE, remoteModuleId)")

    @Synchronized
    fun updateStats(remoteModuleId: String, rttMs: Long, lossPercent: Double, jitterMs: Long) {
        updateGroupStats(remoteModuleId, rttMs, lossPercent, jitterMs)
    }

    @Synchronized
    fun resetRemote(remoteModuleId: String) {
        resetGroup(remoteModuleId)
    }

    @Synchronized
    fun all(): List<QosSnapshot> =
        meshStore.meshSnapshots().map { it.second } + unicastSnapshots.values

    @Synchronized
    fun allScoped(): List<Pair<MediaBearerScope, QosSnapshot>> =
        meshStore.meshSnapshots() + unicastSnapshots.values.map { MediaBearerScope.UNICAST to it }

    @Synchronized
    fun formatSummary(): String {
        val merged = allScoped()
        if (merged.isEmpty()) return "QoS: n/a"
        return merged.joinToString(" | ") { (scope, snap) ->
            "[scope=$scope ${snap.remoteModuleId} rtt=${snap.rttMs}ms loss=${snap.packetLossPercent}% ice=${snap.iceState}]"
        }
    }

    @Synchronized
    fun isMeshConnected(scope: MediaBearerScope, remoteModuleId: String): Boolean =
        IceConnectivity.isConnected(snapshot(scope, remoteModuleId)?.iceState)

    @Synchronized
    fun isGroupConnected(remoteModuleId: String): Boolean =
        isMeshConnected(MediaBearerScope.GROUP, remoteModuleId)

    @Synchronized
    fun isConferenceConnected(remoteModuleId: String): Boolean =
        isMeshConnected(MediaBearerScope.CONFERENCE, remoteModuleId)

    @Synchronized
    fun isUnicastConnected(sessionId: String): Boolean =
        IceConnectivity.isConnected(unicastSnapshots[sessionId]?.iceState)
}
