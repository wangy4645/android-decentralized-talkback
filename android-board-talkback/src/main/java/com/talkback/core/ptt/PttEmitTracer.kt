package com.talkback.core.ptt

import com.talkback.core.util.TalkbackLog

/**
 * PTT emission causal chain — read-only instrumentation.
 *
 * Grep: PTT_UI_TRIGGER | PTT_DECISION | PTT_EMIT_BLOCKED
 *
 * Contract: every UI press must produce UI_TRIGGER then DECISION(ok|blocked) or EMIT_BLOCKED.
 * Successful remote send continues into [FloorRequestCallsiteTracer] (FLOOR_REQUEST_CALLSITE).
 */
object PttEmitTracer {

    private var logSink: ((String) -> Unit)? = null

    fun resetForTests(sink: ((String) -> Unit)? = {}) {
        logSink = sink
    }

    fun callStackHash(skipFrames: Int = 3, frameCount: Int = 6): String {
        val frames = Thread.currentThread().stackTrace
        val digest = frames
            .drop(skipFrames)
            .take(frameCount)
            .joinToString(">") { "${it.fileName}:${it.methodName}:${it.lineNumber}" }
        val hash = digest.hashCode()
        return (if (hash < 0) -hash else hash).toString(16)
    }

    fun recordUiTrigger(
        sessionId: String,
        localModuleId: String?,
        source: String,
        detail: String? = null
    ) {
        emit(
            prefix = "PTT_UI_TRIGGER",
            sessionId = sessionId,
            localModuleId = localModuleId,
            fields = buildMap {
                put("source", source)
                if (detail != null) put("detail", detail)
            }
        )
    }

    fun recordDecision(
        sessionId: String,
        localModuleId: String?,
        ok: Boolean,
        reason: String,
        detail: String? = null,
        traceId: Long? = null
    ) {
        emit(
            prefix = "PTT_DECISION",
            sessionId = sessionId,
            localModuleId = localModuleId,
            fields = buildMap {
                put("decision", if (ok) "ok" else "blocked")
                put("reason", reason)
                if (detail != null) put("detail", detail)
                if (traceId != null) put("traceId", traceId.toString())
            }
        )
    }

    fun recordBlocked(
        sessionId: String,
        localModuleId: String?,
        stage: String,
        reason: String,
        detail: String? = null
    ) {
        emit(
            prefix = "PTT_EMIT_BLOCKED",
            sessionId = sessionId,
            localModuleId = localModuleId,
            fields = buildMap {
                put("stage", stage)
                put("reason", reason)
                if (detail != null) put("detail", detail)
            }
        )
        recordDecision(
            sessionId = sessionId,
            localModuleId = localModuleId,
            ok = false,
            reason = reason,
            detail = detail?.let { "stage=$stage $it" } ?: "stage=$stage"
        )
    }

    private fun emit(
        prefix: String,
        sessionId: String,
        localModuleId: String?,
        fields: Map<String, String>
    ) {
        val threadId = Thread.currentThread().id
        val stackHash = callStackHash()
        val line = buildString {
            append(prefix)
            append(" sid=").append(sessionId)
            append(" local=").append(localModuleId ?: "null")
            append(" threadId=").append(threadId)
            append(" callStackHash=").append(stackHash)
            fields.forEach { (k, v) ->
                append(' ').append(k).append('=').append(v)
            }
        }
        (logSink ?: { TalkbackLog.i(it) })(line)
    }
}
