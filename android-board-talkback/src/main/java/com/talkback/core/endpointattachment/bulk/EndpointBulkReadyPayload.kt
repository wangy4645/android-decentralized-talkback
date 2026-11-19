package com.talkback.core.endpointattachment.bulk

import org.json.JSONObject

data class EndpointBulkReadyPayload(
    val attachmentId: String,
) {
    fun encode(): String = JSONObject().put("attachmentId", attachmentId).toString()

    companion object {
        fun decode(raw: String): EndpointBulkReadyPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val id = JSONObject(raw).optString("attachmentId", "")
                if (id.isBlank()) null else EndpointBulkReadyPayload(id)
            }.getOrNull()
        }
    }
}
