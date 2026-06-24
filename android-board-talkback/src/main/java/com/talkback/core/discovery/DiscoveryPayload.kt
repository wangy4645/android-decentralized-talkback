package com.talkback.core.discovery

import com.talkback.core.model.ModuleId
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveryPeerEntry(
    val moduleId: ModuleId,
    val host: String,
    val signalingPort: Int,
    val endpointCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("moduleId", moduleId.value)
        .put("host", host)
        .put("signalingPort", signalingPort)
        .put("endpointCount", endpointCount)

    fun toPresence(lastSeenMs: Long = System.currentTimeMillis()): ModulePresence =
        ModulePresence(
            moduleId = moduleId,
            host = host,
            port = signalingPort,
            endpointCount = endpointCount,
            lastSeenMs = lastSeenMs
        )

    companion object {
        fun fromJson(json: JSONObject): DiscoveryPeerEntry? {
            val moduleId = runCatching { ModuleId(json.getString("moduleId")) }.getOrNull() ?: return null
            val host = json.optString("host", "")
            val port = json.optInt("signalingPort", json.optInt("port", -1))
            if (host.isBlank() || port <= 0) return null
            return DiscoveryPeerEntry(
                moduleId = moduleId,
                host = host,
                signalingPort = port,
                endpointCount = json.optInt("endpointCount", 0)
            )
        }
    }
}

object DiscoveryPayload {
    const val SESSION_ID = "discovery"

    fun encodeProbe(self: DiscoveryPeerEntry?): String {
        if (self == null) return ""
        return JSONObject().put("self", self.toJson()).toString()
    }

    fun decodeProbe(payload: String): DiscoveryPeerEntry? {
        if (payload.isBlank()) return null
        return runCatching {
            val json = JSONObject(payload)
            json.optJSONObject("self")?.let { DiscoveryPeerEntry.fromJson(it) }
        }.getOrNull()
    }

    fun encodeAnnounce(self: DiscoveryPeerEntry, knownPeers: List<DiscoveryPeerEntry>): String {
        val peers = JSONArray()
        knownPeers.forEach { peers.put(it.toJson()) }
        return JSONObject()
            .put("self", self.toJson())
            .put("knownPeers", peers)
            .toString()
    }

    fun decodeAnnounce(payload: String): Pair<DiscoveryPeerEntry, List<DiscoveryPeerEntry>>? {
        if (payload.isBlank()) return null
        return runCatching {
            val json = JSONObject(payload)
            val self = DiscoveryPeerEntry.fromJson(json.getJSONObject("self")) ?: return@runCatching null
            val known = json.optJSONArray("knownPeers") ?: JSONArray()
            val peers = (0 until known.length()).mapNotNull { i ->
                DiscoveryPeerEntry.fromJson(known.getJSONObject(i))
            }
            self to peers
        }.getOrNull()
    }
}
