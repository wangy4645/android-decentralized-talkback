package com.talkback.core.session

/**
 * PR5-2c-C debug injection surface (ADR-0022 E.14.14 / E.14.15).
 *
 * COMPILE CLOSURE ONLY for P2 PR-A: production Controller already references this type.
 * Default runtime behavior is inert unless an exercise explicitly arms fences.
 * Not an exercise primitive; harness semantics remain HOLD / P3.
 */
object Pr52cDebugInjection {
    /** Seam label for the only authorized production-drain bypass during validation #2. */
    const val DEBUG_RELEASE_SEAM = "DEBUG_RELEASE_DISPATCH"

    private fun edgeKey(sessionId: String, remoteModuleId: String): String =
        sessionId + "|" + remoteModuleId

    private val dispatchBlocked = mutableSetOf<String>()
    private val negotiationForced = mutableSetOf<String>()
    private val validationFenceIntentId = mutableMapOf<String, String>()

    fun blockDispatch(sessionId: String, remoteModuleId: String) {
        dispatchBlocked.add(edgeKey(sessionId, remoteModuleId))
    }

    fun releaseDispatch(sessionId: String, remoteModuleId: String) {
        dispatchBlocked.remove(edgeKey(sessionId, remoteModuleId))
    }

    fun isDispatchBlocked(sessionId: String, remoteModuleId: String): Boolean =
        dispatchBlocked.contains(edgeKey(sessionId, remoteModuleId))

    fun forceNegotiationExecutable(sessionId: String, remoteModuleId: String) {
        negotiationForced.add(edgeKey(sessionId, remoteModuleId))
    }

    fun clearNegotiationForced(sessionId: String, remoteModuleId: String) {
        negotiationForced.remove(edgeKey(sessionId, remoteModuleId))
    }

    fun isNegotiationForced(sessionId: String, remoteModuleId: String): Boolean =
        negotiationForced.contains(edgeKey(sessionId, remoteModuleId))

    fun armValidationFence(sessionId: String, remoteModuleId: String, intentId: String) {
        validationFenceIntentId[edgeKey(sessionId, remoteModuleId)] = intentId
    }

    fun clearValidationFence(sessionId: String, remoteModuleId: String) {
        validationFenceIntentId.remove(edgeKey(sessionId, remoteModuleId))
    }

    fun fencedIntentId(sessionId: String, remoteModuleId: String): String? =
        validationFenceIntentId[edgeKey(sessionId, remoteModuleId)]

    fun shouldSuppressProductionDeferredDrain(
        sessionId: String,
        remoteModuleId: String,
        intentId: String,
        seam: String
    ): Boolean {
        val fenced = validationFenceIntentId[edgeKey(sessionId, remoteModuleId)]
        if (fenced == null || fenced != intentId) return false
        return seam != DEBUG_RELEASE_SEAM
    }

    fun clearEdge(sessionId: String, remoteModuleId: String) {
        val key = edgeKey(sessionId, remoteModuleId)
        dispatchBlocked.remove(key)
        negotiationForced.remove(key)
        validationFenceIntentId.remove(key)
    }
}