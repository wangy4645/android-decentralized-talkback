package com.talkback.core.conference.transport

import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import java.util.concurrent.atomic.AtomicReference

/**
 * P1 option-3: observe datagrams as they arrive on the multicast socket (no admission changes).
 * Emits 10s wall-clock buckets to MEETING_MEDIA_SHADOW for A/B/C layering in the field.
 */
object P1MulticastRxDatagramSourceTelemetry {
    private const val BUCKET_MS = 10_000L
    private const val TOP_SOURCES_PER_BUCKET = 6

    /** Extra IPv4 strings treated as local echo (e.g. device Wi‑Fi address from session wiring). */
    @Volatile
    var extraLocalIpv4: Set<String> = emptySet()

    private data class SourceKey(
        val host: String,
        val port: Int,
        val ssrc: Int?,
    )

    private class BucketState(
        val windowStartMs: Long,
    ) {
        var total: Long = 0
        var localEcho: Long = 0
        var remote: Long = 0
        val bySource = linkedMapOf<SourceKey, Long>()
    }

    private val activeBucket = AtomicReference<BucketState?>(null)

    fun recordDatagram(
        srcHost: String,
        srcPort: Int,
        payload: ByteArray,
        length: Int,
        rxWallMs: Long,
        socketLocalHost: String?,
    ) {
        val ssrc = parseRtpSsrc(payload, length)
        val localEcho = isLocalEcho(srcHost, socketLocalHost)
        val windowStartMs = (rxWallMs / BUCKET_MS) * BUCKET_MS
        var bucket = activeBucket.get()
        if (bucket == null || bucket.windowStartMs != windowStartMs) {
            bucket?.let { flushBucket(it) }
            bucket = BucketState(windowStartMs)
            activeBucket.set(bucket)
        }
        bucket.total++
        if (localEcho) {
            bucket.localEcho++
        } else {
            bucket.remote++
            val key = SourceKey(srcHost, srcPort, ssrc)
            bucket.bySource[key] = (bucket.bySource[key] ?: 0L) + 1L
        }
    }

    /** Desk / test hook. */
    internal fun flushActiveForTest(nowMs: Long = System.currentTimeMillis()) {
        val bucket = activeBucket.get()
        if (bucket != null && bucket.total > 0L) {
            flushBucket(bucket, forceEndMs = nowMs)
            activeBucket.set(null)
        }
    }

    internal fun resetForTest() {
        activeBucket.set(null)
        extraLocalIpv4 = emptySet()
    }

    private fun flushBucket(
        bucket: BucketState,
        forceEndMs: Long = bucket.windowStartMs + BUCKET_MS,
    ) {
        if (bucket.total <= 0L) return
        val topRemote =
            bucket.bySource.entries
                .sortedByDescending { it.value }
                .take(TOP_SOURCES_PER_BUCKET)
                .joinToString(";") { (k, count) ->
                    val ssrcPart = k.ssrc?.let { "ssrc=$it" } ?: "ssrc=na"
                    "${k.host}:${k.port}/$ssrcPart:cnt=$count"
                }
        Profile01ShadowRuntimeObservability.logRxDatagramSourceBucket(
            windowStartMs = bucket.windowStartMs,
            windowEndMs = forceEndMs,
            total = bucket.total,
            localEcho = bucket.localEcho,
            remote = bucket.remote,
            topRemote = topRemote.ifEmpty { "none" },
        )
    }

    internal fun parseRtpSsrc(
        payload: ByteArray,
        length: Int,
    ): Int? {
        if (length < 12) return null
        val v = payload[0].toInt() and 0xC0
        if (v != 0x80) return null
        return ((payload[8].toInt() and 0xff) shl 24) or
            ((payload[9].toInt() and 0xff) shl 16) or
            ((payload[10].toInt() and 0xff) shl 8) or
            (payload[11].toInt() and 0xff)
    }

    private fun isLocalEcho(
        srcHost: String,
        socketLocalHost: String?,
    ): Boolean {
        if (srcHost == "127.0.0.1" || srcHost.startsWith("127.")) return true
        if (socketLocalHost != null && srcHost == socketLocalHost) return true
        if (srcHost in extraLocalIpv4) return true
        return false
    }
}
