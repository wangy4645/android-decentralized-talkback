package com.talkback.core.conference.session.integration

import android.util.Log
import com.talkback.core.conference.session.integration.cutover.ReplacementCutoverRc1
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Meeting Product Integration — Phase A shadow mode.
 *
 * Real Meeting lifecycle drives [ConferenceSessionMediaCoordinatorDelegate] hooks.
 * ADR-0056 remains authoritative for user-hearable audio; shadow uses
 * [com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly.createShadow]
 * (real multicast runtime, no AudioTrack).
 */
object MeetingProductMediaShadow {
    const val LOG_TAG = "MEETING_MEDIA_SHADOW"
    const val EVIDENCE_SCOPE = "MEETING_PRODUCT_SHADOW_PHASE_A"

    /** Phase A default ON — shadow path active; does not cut over ADR-0056. */
    @Volatile
    var enabled: Boolean = true

    val observability: MeetingProductMediaShadowObservability = MeetingProductMediaShadowObservability()

    internal fun runShadowHook(
        hook: ShadowHook,
        sessionId: String,
        moduleId: String? = null,
        block: () -> Unit,
    ) {
        if (!enabled) return
        observability.recordHookInvoked(hook, sessionId, moduleId)
        try {
            block()
        } catch (t: Throwable) {
            observability.recordFailed(hook, sessionId, moduleId, t)
            ReplacementCutoverRc1.onReplacementRuntimeFatal(sessionId)
            logWarn("SHADOW_FAILED hook=$hook session=$sessionId module=$moduleId: ${t.message}", t)
        }
    }

    private fun logInfo(message: String) {
        try {
            Log.i(LOG_TAG, message)
        } catch (_: Throwable) {
        }
    }

    private fun logWarn(
        message: String,
        error: Throwable? = null,
    ) {
        try {
            if (error != null) {
                Log.w(LOG_TAG, message, error)
            } else {
                Log.w(LOG_TAG, message)
            }
        } catch (_: Throwable) {
        }
    }
}

enum class ShadowHook {
    SESSION_STARTED,
    MEMBER_MEDIA_READY,
    MEMBER_REMOVED,
    MEMBER_REPLACED,
    SESSION_STOPPED,
}

enum class ShadowOutcome {
    APPLIED,
    DEFERRED_NO_FACT,
    DEFERRED_NO_BINDING,
    DEFERRED_NO_REPLACE_PAIR,
    DEFERRED_REPLACE_PENDING,
    DEFERRED_NO_INCARNATION,
    DEFERRED_NO_GENERATION,
    WIRING_REJECTED,
    WIRING_NOT_CONFIGURED,
}

/**
 * Phase A observability — A1 lifecycle parity, A3 isolation (failures recorded only).
 */
class MeetingProductMediaShadowObservability {
    private val hookCounts = ConcurrentHashMap<ShadowHook, AtomicLong>()
    private val outcomeCounts = ConcurrentHashMap<String, AtomicLong>()
    private val failures = ConcurrentHashMap<String, AtomicLong>()
    private val lastEvents = ConcurrentHashMap<String, String>()

    fun recordHookInvoked(
        hook: ShadowHook,
        sessionId: String,
        moduleId: String?,
    ) {
        hookCounts.getOrPut(hook) { AtomicLong() }.incrementAndGet()
        val key = eventKey(hook, sessionId, moduleId)
        lastEvents[key] = "INVOKED"
    }

    fun recordOutcome(
        hook: ShadowHook,
        sessionId: String,
        moduleId: String?,
        outcome: ShadowOutcome,
    ) {
        outcomeCounts.getOrPut(outcome.name) { AtomicLong() }.incrementAndGet()
        val key = eventKey(hook, sessionId, moduleId)
        lastEvents[key] = outcome.name
        logInfo("SHADOW hook=$hook session=$sessionId module=$moduleId outcome=$outcome")
    }

    fun recordFailed(
        hook: ShadowHook,
        sessionId: String,
        moduleId: String?,
        error: Throwable,
    ) {
        failures.getOrPut(hook.name) { AtomicLong() }.incrementAndGet()
        val key = eventKey(hook, sessionId, moduleId)
        lastEvents[key] = "SHADOW_FAILED:${error.javaClass.simpleName}"
        logWarn(
            "SHADOW_FAILED hook=$hook session=$sessionId module=$moduleId: ${error.message}",
            error,
        )
    }

    fun snapshot(): Map<String, Any> =
        mapOf(
            "evidenceScope" to MeetingProductMediaShadow.EVIDENCE_SCOPE,
            "enabled" to MeetingProductMediaShadow.enabled,
            "hookCounts" to hookCounts.mapValues { it.value.get() },
            "outcomeCounts" to outcomeCounts.mapValues { it.value.get() },
            "failureCounts" to failures.mapValues { it.value.get() },
            "recentEvents" to lastEvents.toMap(),
        )

    private fun eventKey(
        hook: ShadowHook,
        sessionId: String,
        moduleId: String?,
    ): String = "$hook:$sessionId:${moduleId ?: "-"}"

    private fun logInfo(message: String) {
        try {
            Log.i(MeetingProductMediaShadow.LOG_TAG, message)
        } catch (_: Throwable) {
        }
    }

    private fun logWarn(
        message: String,
        error: Throwable? = null,
    ) {
        try {
            if (error != null) {
                Log.w(MeetingProductMediaShadow.LOG_TAG, message, error)
            } else {
                Log.w(MeetingProductMediaShadow.LOG_TAG, message)
            }
        } catch (_: Throwable) {
        }
    }
}
