package com.talkback.core.endpointtext

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.UUID

/**
 * Endpoint Text control-plane logic: validate, rate-limit, encode, decode, dedup.
 * Coordinator only routes; this module owns text-domain behavior (ADR-0039).
 */
class EndpointTextController(
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
        to: EndpointAddress,
        text: String,
        priority: String = "INLINE",
        sessionHint: String? = null,
        messageId: String? = null
    ): EndpointTextPrepareResult {
        if (text.length > maxTextChars) {
            return EndpointTextPrepareResult.Rejected(REASON_TEXT_TOO_LONG)
        }
        val rateKey = rateLimitKey(from.key, to.key)
        val now = clockMs()
        val last = lastSendAtMsByKey[rateKey]
        if (last != null && now - last < rateLimitWindowMs) {
            onLog("ENDPOINT_TEXT rate_limited from=${from.key} to=${to.key}")
            return EndpointTextPrepareResult.RateLimited
        }
        lastSendAtMsByKey[rateKey] = now
        trimRateLimitMap()
        val id = messageId?.takeIf { it.isNotBlank() } ?: newMessageId()
        val payload = EndpointTextPayload(
            messageId = id,
            text = text,
            priority = priority,
            sessionHint = sessionHint
        )
        onLog("ENDPOINT_TEXT send prepared messageId=$id from=${from.key} to=${to.key}")
        return EndpointTextPrepareResult.Ready(payload)
    }

    fun onReceive(signal: SignalEnvelope): EndpointTextEvent? {
        if (signal.type != SignalType.ENDPOINT_TEXT) return null
        val payload = EndpointTextPayload.decode(signal.payload) ?: run {
            onLog("ENDPOINT_TEXT recv invalid_payload from=${signal.from.key}")
            return null
        }
        if (payload.messageId.isBlank()) {
            onLog("ENDPOINT_TEXT recv blank_messageId from=${signal.from.key}")
            return null
        }
        if (payload.text.length > maxTextChars) {
            onLog("ENDPOINT_TEXT recv text_too_long messageId=${payload.messageId}")
            return null
        }
        val to = signal.to ?: run {
            onLog("ENDPOINT_TEXT recv missing_to messageId=${payload.messageId}")
            return null
        }
        synchronized(recentMessageIds) {
            if (recentMessageIds.containsKey(payload.messageId)) {
                onLog("ENDPOINT_TEXT dedup messageId=${payload.messageId}")
                return null
            }
            recentMessageIds[payload.messageId] = clockMs()
        }
        onLog(
            "ENDPOINT_TEXT recv messageId=${payload.messageId} from=${signal.from.key} " +
                "to=${to.key} priority=${payload.priority}" +
                (payload.sessionHint?.let { " sessionHint=$it" } ?: "")
        )
        return EndpointTextEvent(
            messageId = payload.messageId,
            from = signal.from,
            to = to,
            text = payload.text,
            priority = payload.priority,
            sessionHint = payload.sessionHint
        )
    }

    private fun rateLimitKey(senderEndpointKey: String, receiverEndpointKey: String): String =
        "$senderEndpointKey|$receiverEndpointKey|${SignalType.ENDPOINT_TEXT.name}"

    private fun trimRateLimitMap() {
        while (lastSendAtMsByKey.size > RATE_LIMIT_MAP_CAP) {
            val eldest = lastSendAtMsByKey.entries.iterator().next()
            lastSendAtMsByKey.remove(eldest.key)
        }
    }

    companion object {
        const val MAX_TEXT_CHARS = 256
        const val RATE_LIMIT_WINDOW_MS = 1_000L
        const val DEDUP_CAPACITY = 256
        private const val RATE_LIMIT_MAP_CAP = 512
        const val REASON_TEXT_TOO_LONG = "TEXT_TOO_LONG"
        const val REASON_UNREACHABLE = "UNREACHABLE"
    }
}

sealed class EndpointTextPrepareResult {
    data class Ready(val payload: EndpointTextPayload) : EndpointTextPrepareResult()
    data object RateLimited : EndpointTextPrepareResult()
    data class Rejected(val reason: String) : EndpointTextPrepareResult()
}
