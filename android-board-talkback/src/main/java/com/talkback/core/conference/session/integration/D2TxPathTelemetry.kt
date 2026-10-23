package com.talkback.core.conference.session.integration

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Per-wall-second TX tap path counts (observe only). */
object D2TxPathTelemetry {
    enum class Event {
        POST_APM_FORWARD,
        PRE_APM_FORWARD,
        POST_APM_FORMAT_REJECT,
        PRE_APM_FORMAT_REJECT,
        SUPPRESSED_PRE_WHILE_POST_ACTIVE,
    }

    private data class SecondBucket(
        val epochSecond: Long,
        val postForward: AtomicInteger = AtomicInteger(),
        val preForward: AtomicInteger = AtomicInteger(),
        val postFormatReject: AtomicInteger = AtomicInteger(),
        val preFormatReject: AtomicInteger = AtomicInteger(),
        val suppressedPre: AtomicInteger = AtomicInteger(),
    )

    private val bucketBySession = ConcurrentHashMap<String, SecondBucket>()

    @Volatile
    var lastForwardedPath: String = "PRE_APM"

    fun record(
        sessionId: String,
        event: Event,
    ) {
        if (!D2SendChainRuntime.captureChainObserveEnabled) return
        val sec = System.currentTimeMillis() / 1000L
        val existing = bucketBySession[sessionId]
        if (existing != null && existing.epochSecond != sec) {
            flush(sessionId, existing)
            bucketBySession.remove(sessionId)
        }
        val bucket =
            bucketBySession.compute(sessionId) { _, cur ->
                if (cur == null || cur.epochSecond != sec) SecondBucket(sec) else cur
            } ?: return
        when (event) {
            Event.POST_APM_FORWARD -> {
                lastForwardedPath = "POST_APM"
                bucket.postForward.incrementAndGet()
            }
            Event.PRE_APM_FORWARD -> {
                lastForwardedPath = "PRE_APM"
                bucket.preForward.incrementAndGet()
            }
            Event.POST_APM_FORMAT_REJECT -> bucket.postFormatReject.incrementAndGet()
            Event.PRE_APM_FORMAT_REJECT -> bucket.preFormatReject.incrementAndGet()
            Event.SUPPRESSED_PRE_WHILE_POST_ACTIVE -> bucket.suppressedPre.incrementAndGet()
        }
    }

    fun clearSession(sessionId: String) {
        bucketBySession.remove(sessionId)?.let { flush(sessionId, it) }
    }

    private fun flush(
        sessionId: String,
        bucket: SecondBucket,
    ) {
        Profile01ShadowRuntimeObservability.logD2TxPathSecond(
            sessionId = sessionId,
            epochSecond = bucket.epochSecond,
            postForward = bucket.postForward.get(),
            preForward = bucket.preForward.get(),
            postFormatReject = bucket.postFormatReject.get(),
            preFormatReject = bucket.preFormatReject.get(),
            suppressedPre = bucket.suppressedPre.get(),
            muxEnabled = D2SendChainRuntime.postApmMuxEnabled,
        )
    }
}
