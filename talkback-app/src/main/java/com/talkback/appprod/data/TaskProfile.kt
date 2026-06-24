package com.talkback.appprod.data

import com.talkback.core.model.EndpointPriority
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TaskProfile(
    val id: String,
    val name: String,
    val sharedSecret: String,
    val channelId: String,
    val channelDisplayName: String,
    val staticPeersJson: String,
    val rfKeyLabel: String = "",
    val localPriority: EndpointPriority = EndpointPriority.NORMAL
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("sharedSecret", sharedSecret)
        put("channelId", channelId)
        put("channelDisplayName", channelDisplayName)
        put("staticPeersJson", staticPeersJson)
        put("rfKeyLabel", rfKeyLabel)
        put("localPriority", localPriority.name)
    }

    companion object {
        fun createNew(
            name: String,
            sharedSecret: String,
            channelId: String = "CH-01",
            channelDisplayName: String = name,
            staticPeersJson: String = "",
            rfKeyLabel: String = "",
            localPriority: EndpointPriority = EndpointPriority.NORMAL
        ): TaskProfile = TaskProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Task" },
            sharedSecret = sharedSecret.trim(),
            channelId = channelId.trim().ifEmpty { "CH-01" },
            channelDisplayName = channelDisplayName.trim().ifEmpty { name },
            staticPeersJson = staticPeersJson,
            rfKeyLabel = rfKeyLabel.trim(),
            localPriority = localPriority
        )

        fun fromJson(json: JSONObject): TaskProfile = TaskProfile(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            sharedSecret = json.optString("sharedSecret", ""),
            channelId = json.optString("channelId", "CH-01"),
            channelDisplayName = json.optString("channelDisplayName", ""),
            staticPeersJson = json.optString("staticPeersJson", ""),
            rfKeyLabel = json.optString("rfKeyLabel", ""),
            localPriority = parsePriority(json.optString("localPriority", "NORMAL"))
        )

        private fun parsePriority(raw: String): EndpointPriority =
            runCatching { EndpointPriority.valueOf(raw.trim().uppercase()) }
                .getOrDefault(EndpointPriority.NORMAL)

        fun encodeList(profiles: List<TaskProfile>): String {
            val array = JSONArray()
            profiles.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun decodeList(raw: String): List<TaskProfile> {
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val profile = fromJson(item)
                    if (profile.id.isNotBlank()) add(profile)
                }
            }
        }
    }
}
