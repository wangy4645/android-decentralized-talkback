package com.talkback.core.discovery

import com.talkback.core.model.ModuleId
import org.json.JSONArray
import org.json.JSONObject

data class StaticPeerEntry(
    val moduleId: ModuleId,
    val host: String,
    val port: Int
)

object StaticPeerConfig {
    fun parse(json: String): List<StaticPeerEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(json)
            val peers = root.optJSONArray("peers") ?: JSONArray(json)
            (0 until peers.length()).mapNotNull { i ->
                val o = peers.getJSONObject(i)
                val moduleId = runCatching { ModuleId(o.getString("moduleId")) }.getOrNull() ?: return@mapNotNull null
                StaticPeerEntry(
                    moduleId = moduleId,
                    host = o.getString("host"),
                    port = o.getInt("port")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encode(entries: List<StaticPeerEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("moduleId", e.moduleId.value)
                    .put("host", e.host)
                    .put("port", e.port)
            )
        }
        return JSONObject().put("peers", arr).toString()
    }
}
