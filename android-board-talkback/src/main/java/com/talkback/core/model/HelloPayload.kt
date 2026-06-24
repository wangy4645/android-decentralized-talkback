package com.talkback.core.model

import org.json.JSONArray
import org.json.JSONObject

data class RemoteEndpointInfo(
    val endpointId: String,
    val displayName: String,
    val online: Boolean,
    val priority: EndpointPriority = EndpointPriority.NORMAL
) {
    fun encodeJson(): JSONObject = JSONObject()
        .put("endpointId", endpointId)
        .put("displayName", displayName)
        .put("online", online)
        .put("priority", priority.name)

    companion object {
        fun decodeJson(json: JSONObject): RemoteEndpointInfo = RemoteEndpointInfo(
            endpointId = json.getString("endpointId"),
            displayName = json.optString("displayName", ""),
            online = json.optBoolean("online", false),
            priority = parsePriority(json.optString("priority", "NORMAL"))
        )

        private fun parsePriority(raw: String): EndpointPriority =
            runCatching { EndpointPriority.valueOf(raw.trim().uppercase()) }
                .getOrDefault(EndpointPriority.NORMAL)
    }
}

data class HelloPayload(
    val moduleId: String,
    val endpoints: List<RemoteEndpointInfo>,
    val charging: Boolean = false,
    val batteryPercent: Int = 100,
    val onlineSinceMs: Long = 0L,
    val anchorEpoch: Long = 0L,
    val primaryModuleId: String? = null,
    val backupModuleId: String? = null,
    val channelId: String? = null,
    val rosterEpoch: Long = 0L,
    val meshGeneration: Long = 0L,
    val memberHash: Int = 0
) {
    fun encode(): String {
        val arr = JSONArray()
        endpoints.forEach { ep -> arr.put(ep.encodeJson()) }
        val json = JSONObject()
            .put("moduleId", moduleId)
            .put("endpoints", arr)
            .put("charging", charging)
            .put("batteryPercent", batteryPercent.coerceIn(0, 100))
        if (onlineSinceMs > 0L) {
            json.put("onlineSinceMs", onlineSinceMs)
        }
        if (anchorEpoch > 0L) {
            json.put("anchorEpoch", anchorEpoch)
        }
        if (!primaryModuleId.isNullOrBlank()) {
            json.put("primaryModuleId", primaryModuleId)
        }
        if (!backupModuleId.isNullOrBlank()) {
            json.put("backupModuleId", backupModuleId)
        }
        if (!channelId.isNullOrBlank()) {
            json.put("channelId", channelId)
        }
        if (rosterEpoch > 0L) {
            json.put("rosterEpoch", rosterEpoch)
        }
        if (meshGeneration > 0L) {
            json.put("meshGeneration", meshGeneration)
        }
        if (memberHash != 0) {
            json.put("memberHash", memberHash)
        }
        return json.toString()
    }

    companion object {
        fun decode(raw: String): HelloPayload? = runCatching {
            val json = JSONObject(raw)
            val arr = json.getJSONArray("endpoints")
            val list = (0 until arr.length()).map { i ->
                RemoteEndpointInfo.decodeJson(arr.getJSONObject(i))
            }
            HelloPayload(
                moduleId = json.getString("moduleId"),
                endpoints = list,
                charging = json.optBoolean("charging", false),
                batteryPercent = json.optInt("batteryPercent", 100),
                onlineSinceMs = json.optLong("onlineSinceMs", 0L),
                anchorEpoch = json.optLong("anchorEpoch", 0L),
                primaryModuleId = json.optString("primaryModuleId").takeIf { it.isNotBlank() },
                backupModuleId = json.optString("backupModuleId").takeIf { it.isNotBlank() },
                channelId = json.optString("channelId").takeIf { it.isNotBlank() },
                rosterEpoch = json.optLong("rosterEpoch", 0L),
                meshGeneration = json.optLong("meshGeneration", 0L),
                memberHash = json.optInt("memberHash", 0)
            )
        }.getOrNull()
    }
}
