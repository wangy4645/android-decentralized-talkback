package com.talkback.core.model

import com.talkback.core.video.VideoMediaPolicy
import org.json.JSONObject

data class VideoPublishPayload(
    val streamId: String,
    val videoGeneration: Long,
    val publisherModuleId: String,
    val authoritative: Boolean,
    val codec: String = VideoMediaPolicy.CODEC_H264,
    val maxBitrateBps: Int? = null,
) {
    fun encode(): String {
        val json = JSONObject()
            .put("streamId", streamId)
            .put("videoGeneration", videoGeneration)
            .put("publisherModuleId", publisherModuleId)
            .put("authoritative", authoritative)
            .put("codec", codec)
        if (maxBitrateBps != null) {
            json.put("maxBitrateBps", maxBitrateBps)
        }
        return json.toString()
    }

    companion object {
        fun decode(raw: String): VideoPublishPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val streamId = json.optString("streamId", "")
                val publisherModuleId = json.optString("publisherModuleId", "")
                if (streamId.isBlank() || publisherModuleId.isBlank()) return null
                VideoPublishPayload(
                    streamId = streamId,
                    videoGeneration = json.optLong("videoGeneration", 0L),
                    publisherModuleId = publisherModuleId,
                    authoritative = json.optBoolean("authoritative", false),
                    codec = json.optString("codec", VideoMediaPolicy.CODEC_H264),
                    maxBitrateBps = if (json.has("maxBitrateBps")) json.optInt("maxBitrateBps") else null,
                )
            }.getOrNull()
        }
    }
}

data class VideoPublishDenyPayload(
    val streamId: String,
    val videoGeneration: Long,
    val currentPublisherModuleId: String,
    val requesterModuleId: String,
) {
    fun encode(): String =
        JSONObject()
            .put("streamId", streamId)
            .put("videoGeneration", videoGeneration)
            .put("currentPublisherModuleId", currentPublisherModuleId)
            .put("requesterModuleId", requesterModuleId)
            .toString()

    companion object {
        fun decode(raw: String): VideoPublishDenyPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val streamId = json.optString("streamId", "")
                val currentPublisherModuleId = json.optString("currentPublisherModuleId", "")
                val requesterModuleId = json.optString("requesterModuleId", "")
                if (streamId.isBlank() || currentPublisherModuleId.isBlank() || requesterModuleId.isBlank()) {
                    return null
                }
                VideoPublishDenyPayload(
                    streamId = streamId,
                    videoGeneration = json.optLong("videoGeneration", 0L),
                    currentPublisherModuleId = currentPublisherModuleId,
                    requesterModuleId = requesterModuleId,
                )
            }.getOrNull()
        }
    }
}

data class VideoStopPayload(
    val streamId: String,
    val videoGeneration: Long,
    val publisherModuleId: String,
) {
    fun encode(): String =
        JSONObject()
            .put("streamId", streamId)
            .put("videoGeneration", videoGeneration)
            .put("publisherModuleId", publisherModuleId)
            .toString()

    companion object {
        fun decode(raw: String): VideoStopPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val streamId = json.optString("streamId", "")
                val publisherModuleId = json.optString("publisherModuleId", "")
                if (streamId.isBlank() || publisherModuleId.isBlank()) return null
                VideoStopPayload(
                    streamId = streamId,
                    videoGeneration = json.optLong("videoGeneration", 0L),
                    publisherModuleId = publisherModuleId,
                )
            }.getOrNull()
        }
    }
}

/** Shared body for VIDEO_OFFER / VIDEO_ANSWER / VIDEO_ICE. */
data class VideoNegotiationPayload(
    val streamId: String,
    val videoGeneration: Long,
    val negotiationId: String,
    val publisherModuleId: String,
    val viewerModuleId: String,
    val sdp: String = "",
    val candidate: String = "",
) {
    fun encode(): String {
        val json = JSONObject()
            .put("streamId", streamId)
            .put("videoGeneration", videoGeneration)
            .put("negotiationId", negotiationId)
            .put("publisherModuleId", publisherModuleId)
            .put("viewerModuleId", viewerModuleId)
        if (sdp.isNotEmpty()) json.put("sdp", sdp)
        if (candidate.isNotEmpty()) json.put("candidate", candidate)
        return json.toString()
    }

    companion object {
        fun decode(raw: String): VideoNegotiationPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val streamId = json.optString("streamId", "")
                val negotiationId = json.optString("negotiationId", "")
                val publisherModuleId = json.optString("publisherModuleId", "")
                val viewerModuleId = json.optString("viewerModuleId", "")
                if (streamId.isBlank() || negotiationId.isBlank() ||
                    publisherModuleId.isBlank() || viewerModuleId.isBlank()
                ) {
                    return null
                }
                VideoNegotiationPayload(
                    streamId = streamId,
                    videoGeneration = json.optLong("videoGeneration", 0L),
                    negotiationId = negotiationId,
                    publisherModuleId = publisherModuleId,
                    viewerModuleId = viewerModuleId,
                    sdp = json.optString("sdp", ""),
                    candidate = json.optString("candidate", ""),
                )
            }.getOrNull()
        }
    }
}
