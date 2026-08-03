package com.talkback.core.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Harness / experiment control: SUPPRESS_SUCCESSOR_ATTEMPT.
 *
 * Blocks successor obligation episode admission for an armed edge within a TTL.
 * Does **not** alter RecoveryDeliveryPolicy, DeferredIntentAuthority,
 * RecoveryCompletionPolicy, or adoption state.
 *
 * Lifecycle facts (harness namespace only — not protocol / recovery domain facts):
 * - SUPPRESS_SUCCESSOR_ATTEMPT_ARMED on arm (even if never hit)
 * - SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED only when admission is actually suppressed
 * - HARNESS_SUCCESSOR_SUPPRESSION_APPLIED co-emitted with APPLIED (experiment marker)
 * - SUPPRESS_SUCCESSOR_ATTEMPT_EXPIRED when TTL elapses on a check
 */
object SuppressSuccessorAttemptDebugInjection {

    const val FACT_ARMED = "SUPPRESS_SUCCESSOR_ATTEMPT_ARMED"
    const val FACT_APPLIED = "SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED"
    const val FACT_EXPIRED = "SUPPRESS_SUCCESSOR_ATTEMPT_EXPIRED"
    /** Explicit harness marker — must not be read as a recovery-domain fact. */
    const val FACT_HARNESS_SUPPRESSION_APPLIED = "HARNESS_SUCCESSOR_SUPPRESSION_APPLIED"

    const val DEFAULT_TTL_MS: Long = 180_000L
    const val LINEAGE_SCOPE_EDGE = "EDGE"

    data class ArmedEdge(
        val sessionId: String,
        val targetModule: String,
        val lineageScope: String,
        val armedAtMs: Long,
        val expiresAtMs: Long,
        val reason: String,
        val token: String
    )

    private val armed = ConcurrentHashMap<String, ArmedEdge>()
    private val applyCount = AtomicInteger(0)
    private var logSink: ((String) -> Unit)? = null

    private fun edgeKey(sessionId: String, targetModule: String): String =
        "$sessionId|$targetModule"

    fun arm(
        sessionId: String,
        targetModule: String,
        ttlMs: Long = DEFAULT_TTL_MS,
        reason: String,
        lineageScope: String = LINEAGE_SCOPE_EDGE,
        nowMs: Long = System.currentTimeMillis(),
        log: ((String) -> Unit)? = null
    ): String {
        require(sessionId.isNotBlank()) { "sessionId required" }
        require(targetModule.isNotBlank()) { "targetModule required" }
        require(ttlMs > 0L) { "ttlMs must be > 0" }
        val token = UUID.randomUUID().toString()
        val edge = ArmedEdge(
            sessionId = sessionId,
            targetModule = targetModule,
            lineageScope = lineageScope,
            armedAtMs = nowMs,
            expiresAtMs = nowMs + ttlMs,
            reason = reason,
            token = token
        )
        armed[edgeKey(sessionId, targetModule)] = edge
        emit(
            log,
            "$FACT_ARMED sessionId=$sessionId targetModule=$targetModule " +
                "lineageScope=$lineageScope armedAt=$nowMs expiresAt=${edge.expiresAtMs} " +
                "ttlMs=$ttlMs reason=$reason token=$token"
        )
        return token
    }

    fun clear(sessionId: String, targetModule: String) {
        armed.remove(edgeKey(sessionId, targetModule))
    }

    fun clearAll() {
        armed.clear()
        applyCount.set(0)
        logSink = null
    }

    /** JVM UT: avoid android.util.Log (not mocked). */
    internal fun resetForTest(log: ((String) -> Unit)? = null) {
        clearAll()
        logSink = log
    }

    fun isArmed(sessionId: String, targetModule: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val edge = armed[edgeKey(sessionId, targetModule)] ?: return false
        if (nowMs >= edge.expiresAtMs) {
            expireIfPresent(edge, nowMs, log = null)
            return false
        }
        return true
    }

    fun applyCount(): Int = applyCount.get()

    fun armedSnapshot(sessionId: String, targetModule: String): ArmedEdge? =
        armed[edgeKey(sessionId, targetModule)]

    /**
     * @return true when successor admission must be suppressed (caller MUST NOT admit).
     */
    fun trySuppressAdmission(
        sessionId: String,
        remoteModuleId: String,
        originalAttemptId: Long,
        generation: Long,
        nowMs: Long = System.currentTimeMillis(),
        log: ((String) -> Unit)? = null
    ): Boolean {
        val key = edgeKey(sessionId, remoteModuleId)
        val edge = armed[key] ?: return false
        if (nowMs >= edge.expiresAtMs) {
            expireIfPresent(edge, nowMs, log)
            return false
        }
        applyCount.incrementAndGet()
        emit(
            log,
            "$FACT_APPLIED sessionId=$sessionId originalAttemptId=$originalAttemptId " +
                "generation=$generation suppressedAt=$nowMs token=${edge.token} " +
                "targetModule=$remoteModuleId"
        )
        emit(
            log,
            "$FACT_HARNESS_SUPPRESSION_APPLIED edge=$key token=${edge.token} " +
                "ttlMs=${edge.expiresAtMs - edge.armedAtMs} activatedAt=$nowMs " +
                "sessionId=$sessionId targetModule=$remoteModuleId " +
                "originalAttemptId=$originalAttemptId generation=$generation " +
                "namespace=HARNESS_ONLY"
        )
        return true
    }

    private fun expireIfPresent(edge: ArmedEdge, nowMs: Long, log: ((String) -> Unit)?) {
        val removed = armed.remove(edgeKey(edge.sessionId, edge.targetModule), edge)
        if (!removed) return
        emit(
            log,
            "$FACT_EXPIRED sessionId=${edge.sessionId} targetModule=${edge.targetModule} " +
                "token=${edge.token} expiredAt=$nowMs expiresAt=${edge.expiresAtMs}"
        )
    }

    private fun emit(log: ((String) -> Unit)?, msg: String) {
        val sink = log ?: logSink
        if (sink != null) sink(msg) else TalkbackLog.i(msg)
    }
}