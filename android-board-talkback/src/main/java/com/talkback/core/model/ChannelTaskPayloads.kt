package com.talkback.core.model

import org.json.JSONArray
import org.json.JSONObject

enum class ChannelTaskPhase {
    OPEN,
    CLOSED,
    EXPIRED,
}

data class TaskIssuePayload(
    val taskId: String,
    val channelId: String,
    val title: String,
    val body: String,
    val deadlineEpochMs: Long,
    val targets: List<String>,
) {
    fun encode(): String {
        val arr = JSONArray()
        targets.forEach { arr.put(it) }
        return JSONObject()
            .put("taskId", taskId)
            .put("channelId", channelId)
            .put("title", title)
            .put("body", body)
            .put("deadlineEpochMs", deadlineEpochMs)
            .put("targets", arr)
            .toString()
    }

    companion object {
        fun decode(raw: String): TaskIssuePayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val taskId = json.optString("taskId", "")
                val channelId = json.optString("channelId", "")
                val title = json.optString("title", "")
                if (taskId.isBlank() || channelId.isBlank() || title.isBlank()) return null
                val targets = mutableListOf<String>()
                val arr = json.optJSONArray("targets")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i).trim().uppercase()
                        if (id.isNotBlank()) targets += id
                    }
                }
                if (targets.isEmpty()) return null
                TaskIssuePayload(
                    taskId = taskId,
                    channelId = channelId,
                    title = title,
                    body = json.optString("body", ""),
                    deadlineEpochMs = json.optLong("deadlineEpochMs", 0L),
                    targets = targets.distinct(),
                )
            }.getOrNull()
        }
    }
}

data class TaskAckPayload(
    val taskId: String,
) {
    fun encode(): String = JSONObject().put("taskId", taskId).toString()

    companion object {
        fun decode(raw: String): TaskAckPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val taskId = JSONObject(raw).optString("taskId", "")
                if (taskId.isBlank()) null else TaskAckPayload(taskId)
            }.getOrNull()
        }
    }
}

data class TaskClosePayload(
    val taskId: String,
    val phase: ChannelTaskPhase,
) {
    fun encode(): String =
        JSONObject()
            .put("taskId", taskId)
            .put("phase", phase.name)
            .toString()

    companion object {
        fun decode(raw: String): TaskClosePayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val taskId = json.optString("taskId", "")
                if (taskId.isBlank()) return null
                val phase = runCatching {
                    ChannelTaskPhase.valueOf(json.optString("phase", ChannelTaskPhase.CLOSED.name))
                }.getOrDefault(ChannelTaskPhase.CLOSED)
                TaskClosePayload(taskId, phase)
            }.getOrNull()
        }
    }
}
