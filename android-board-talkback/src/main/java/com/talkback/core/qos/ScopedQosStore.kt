package com.talkback.core.qos

import com.talkback.core.webrtc.MediaBearerScope

/**
 * ADR-0052: Isolated QoS snapshots per mesh bearer scope (GROUP vs CONFERENCE).
 * UNICAST remains in [NetworkQualityMonitor] session-keyed table.
 */
internal class ScopedQosStore {
    private val meshByScope = mutableMapOf<MediaBearerScope, MutableMap<String, QosSnapshot>>()

    @Synchronized
    fun updateIceState(scope: MediaBearerScope, remoteModuleId: String, iceState: String) {
        requireMeshScope(scope)
        val table = meshTable(scope)
        val prev = table[remoteModuleId]
        table[remoteModuleId] = (prev ?: QosSnapshot(remoteModuleId)).copy(
            iceState = iceState,
            updatedMs = System.currentTimeMillis()
        )
        QosScopeTraceLog.iceUpdate(scope, remoteModuleId, iceState)
    }

    @Synchronized
    fun updateGroupStats(
        remoteModuleId: String,
        rttMs: Long,
        lossPercent: Double,
        jitterMs: Long
    ) {
        val table = meshTable(MediaBearerScope.GROUP)
        table[remoteModuleId] = QosSnapshot(
            remoteModuleId = remoteModuleId,
            rttMs = rttMs,
            packetLossPercent = lossPercent,
            jitterMs = jitterMs,
            iceState = table[remoteModuleId]?.iceState ?: "UNKNOWN"
        )
    }

    @Synchronized
    fun snapshot(scope: MediaBearerScope, key: String): QosSnapshot? {
        requireMeshScope(scope)
        return meshTable(scope)[key]
    }

    @Synchronized
    fun reset(scope: MediaBearerScope, key: String) {
        requireMeshScope(scope)
        meshTable(scope).remove(key)
    }

    @Synchronized
    fun meshSnapshots(): List<Pair<MediaBearerScope, QosSnapshot>> =
        meshByScope.flatMap { (scope, table) -> table.values.map { scope to it } }

    private fun meshTable(scope: MediaBearerScope): MutableMap<String, QosSnapshot> =
        meshByScope.getOrPut(scope) { mutableMapOf() }

    private fun requireMeshScope(scope: MediaBearerScope) {
        require(scope == MediaBearerScope.GROUP || scope == MediaBearerScope.CONFERENCE) {
            "Mesh QoS scope must be GROUP or CONFERENCE, got $scope"
        }
    }
}

internal object QosScopeTraceLog {
    fun iceUpdate(scope: MediaBearerScope, peer: String, ice: String) {
        android.util.Log.i(
            "Talkback",
            "QOS_SCOPE_TRACE update scope=$scope peer=$peer ice=$ice"
        )
    }
}
