package com.talkback.core.channeltext

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.UUID

/**
 * ChannelText control-plane logic (ADR-0041). Coordinator routes; this owns text-domain behavior.
 */
class ChannelTextController(
    private val maxTextChars: Int = MAX_TEXT_CHARS,
    private val rateLimitWindowMs: Long = RATE_LIMIT_WINDOW_MS,
    private val dedupCapacity: Int = DEDUP_CAPACITY,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
    private val onLog: (String) -> Unit = {}
) {
    private val lastSendAtMsByKey = LinkedHashMap<String, Long>(16, 0.75f, true)
    private val recentMessageIds = object : LinkedHashMap<String, Long>(dedupCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > dedupCapacity
    }

    fun prepareSend(
        from: EndpointAddress,
        channelId: String,
        text: String,
        messageId: String? = null
    ): ChannelTextPrepareResult {
        if (channelId.isBlank()) {
            return ChannelTextPrepareResult.Rejected(REASON_INVALID_CHANNEL)
        }
        if (text.length > maxTextChars) {
            return ChannelTextPrepareResult.Rejected(REASON_TEXT_TOO_LONG)
        }
        val rateKey = rateLimitKey(from.key, channelId)
        val now = clockMs()
        val last = lastSendAtMsByKey[rateKey]
        if (last != null && now - last < rateLimitWindowMs) {
            onLog("CHANNEL_TEXT rate_limited from=${from.key} channelId=$channelId")
            return ChannelTextPrepareResult.RateLimited
        }
        val id = messageId?.takeIf { it.isNotBlank() } ?: newMessageId()
        val payload = ChannelTextPayload(
            messageId = id,
            channelId = channelId,
            text = text
        )
        onLog("CHANNEL_TEXT send prepared messageId=$id from=${from.key} channelId=$channelId")
        return ChannelTextPrepareResult.Ready(payload)
    }

    fun markSent(from: EndpointAddress, channelId: String) {
        lastSendAtMsByKey[rateLimitKey(from.key, channelId)] = clockMs()
        while (lastSendAtMsByKey.size > RATE_LIMIT_MAP_CAP) {
            val eldest = lastSendAtMsByKey.entries.iterator().next()
            lastSendAtMsByKey.remove(eldest.key)
        }
    }

    fun onReceive(signal: SignalEnvelope): ChannelTextEvent? {
        if (signal.type != SignalType.CHANNEL_TEXT) return null
        val payload = ChannelTextPayload.decode(signal.payload) ?: run {
            onLog("CHANNEL_TEXT recv invalid_payload from=${signal.from.key}")
            return null
        }
        if (payload.messageId.isBlank() || payload.channelId.isBlank()) {
            onLog("CHANNEL_TEXT recv blank_id from=${signal.from.key}")
            return null
        }
        if (payload.text.length > maxTextChars) {
            onLog("CHANNEL_TEXT recv text_too_long messageId=${payload.messageId}")
            return null
        }
        synchronized(recentMessageIds) {
            if (recentMessageIds.containsKey(payload.messageId)) {
                onLog("CHANNEL_TEXT dedup messageId=${payload.messageId}")
                return null
            }
            recentMessageIds[payload.messageId] = clockMs()
        }
        onLog(
            "CHANNEL_TEXT recv messageId=${payload.messageId} channelId=${payload.channelId} " +
                "from=${signal.from.key}"
        )
        return ChannelTextEvent(
            messageId = payload.messageId,
            channelId = payload.channelId,
            from = signal.from,
            text = payload.text,
            priority = payload.priority
        )
    }

    private fun rateLimitKey(senderEndpointKey: String, channelId: String): String =
        "$senderEndpointKey|$channelId|${SignalType.CHANNEL_TEXT.name}"

    companion object {
        const val MAX_TEXT_CHARS = 256
        const val RATE_LIMIT_WINDOW_MS = 1_000L
        const val DEDUP_CAPACITY = 256
        private const val RATE_LIMIT_MAP_CAP = 512
        const val REASON_TEXT_TOO_LONG = "TEXT_TOO_LONG"
        const val REASON_INVALID_CHANNEL = "INVALID_CHANNEL"
        const val REASON_NO_MEMBERS = "NO_MEMBERS"
        const val REASON_UNREACHABLE = "UNREACHABLE"
        const val REASON_SEND_FAILED = "SEND_FAILED"
    }
}

sealed class ChannelTextPrepareResult {
    data class Ready(val payload: ChannelTextPayload) : ChannelTextPrepareResult()
    data object RateLimited : ChannelTextPrepareResult()
    data class Rejected(val reason: String) : ChannelTextPrepareResult()
}
