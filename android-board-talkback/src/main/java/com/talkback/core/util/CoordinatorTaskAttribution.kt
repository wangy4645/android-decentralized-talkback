package com.talkback.core.util

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Observe-only coordinator occupancy. Does not change scheduling.
 *
 * Grep: COORD_TASK, COORD_SPAN, COORD_THREAD
 *
 * COORD_TASK = an actual executor enqueue (POST/RUN/END).
 * COORD_SPAN = a classified LATE_PEER / OFFER / SHADOW call, which may be
 * inline on an already-running coordinator turn (not a new queued task).
 */
object CoordinatorTaskAttribution {
    enum class Kind { LATE_PEER, OFFER, SHADOW, OTHER }

    data class Scope(val kind: Kind, val identity: String)

    private val tls = ThreadLocal<Scope>()
    private val seq = AtomicLong(0)
    private val shadowOnCoordinatorLogged = AtomicBoolean(false)
    private val shadowOffCoordinatorLogged = AtomicBoolean(false)
    @Volatile
    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        tls.remove()
        logSink = sink
        shadowOnCoordinatorLogged.set(false)
        shadowOffCoordinatorLogged.set(false)
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) sink(message) else TalkbackLog.i(message)
    }

    fun current(): Scope? = tls.get()

    fun nextSeq(): Long = seq.incrementAndGet()

    fun <T> scoped(kind: Kind, identity: String, block: () -> T): T {
        val prev = tls.get()
        tls.set(Scope(kind, identity))
        val onCoordinator = isCoordinatorThread()
        val t0 = System.currentTimeMillis()
        try {
            return block()
        } finally {
            val execMs = System.currentTimeMillis() - t0
            log(
                "COORD_SPAN kind=${kind.name} identity=$identity " +
                    "onCoordinator=$onCoordinator execMs=$execMs nested=${prev != null}",
            )
            if (prev != null) tls.set(prev) else tls.remove()
        }
    }

    fun post(seq: Long, kind: Kind, identity: String, mode: String, queueSize: Int) {
        log(
            "COORD_TASK stage=POST seq=$seq kind=${kind.name} mode=$mode " +
                "identity=$identity queueSize=$queueSize thread=${Thread.currentThread().name}",
        )
    }

    fun run(
        seq: Long,
        kind: Kind,
        identity: String,
        mode: String,
        waitMs: Long,
        queueSize: Int,
    ) {
        log(
            "COORD_TASK stage=RUN seq=$seq kind=${kind.name} mode=$mode " +
                "identity=$identity waitMs=$waitMs queueSize=$queueSize " +
                "thread=${Thread.currentThread().name}",
        )
    }

    fun end(
        seq: Long,
        kind: Kind,
        identity: String,
        mode: String,
        waitMs: Long,
        execMs: Long,
        queueSize: Int,
    ) {
        log(
            "COORD_TASK stage=END seq=$seq kind=${kind.name} mode=$mode " +
                "identity=$identity waitMs=$waitMs execMs=$execMs queueSize=$queueSize " +
                "thread=${Thread.currentThread().name}",
        )
    }

    fun shadowSignedFactIdentity(
        sessionId: String,
        peerModuleId: String,
        factType: Int?,
        factDigestHex: String?,
        conferenceEpoch: Long?,
        membershipVersion: Long?,
        mediaKeyEpoch: Long?,
        headDigestHex: String?,
    ): String {
        fun token(value: Any?): String = value?.toString() ?: "-"
        return "path=SIGNED_FACT session=$sessionId peer=$peerModuleId " +
            "factType=${token(factType)} factDigest=${token(factDigestHex)} " +
            "conferenceEpoch=${token(conferenceEpoch)} membershipVersion=${token(membershipVersion)} " +
            "mediaKeyEpoch=${token(mediaKeyEpoch)} headDigest=${token(headDigestHex)}"
    }

    /** One-shot: whether SHADOW runtime logs run on the coordinator thread. */
    fun noteShadowRuntimeThread() {
        val onCoordinator = isCoordinatorThread()
        val first =
            if (onCoordinator) {
                shadowOnCoordinatorLogged.compareAndSet(false, true)
            } else {
                shadowOffCoordinatorLogged.compareAndSet(false, true)
            }
        if (!first) return
        log(
            "COORD_THREAD kind=SHADOW onCoordinator=$onCoordinator " +
                "thread=${Thread.currentThread().name}",
        )
    }

    fun isCoordinatorThread(): Boolean =
        Thread.currentThread().name.startsWith("talkback-coordinator")
}
