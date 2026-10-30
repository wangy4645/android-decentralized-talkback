package com.talkback.core.conference.runtime

/**
 * Install / HARD FENCE registry for AdmittedMediaSource execution incarnations.
 * Packet arrival has no API here and MUST NOT create entries.
 */
class AdmittedMediaSourceRegistry {
    private val byIdentity = linkedMapOf<String, InstalledExecution>()

    fun install(source: AdmittedMediaSource) {
        byIdentity[source.sourceIdentity] =
            InstalledExecution(source = source, fence = ExecutionFenceState.OPEN)
    }

    fun hardFence(sourceIdentity: String, incarnationId: Long): Boolean {
        val current = byIdentity[sourceIdentity] ?: return false
        if (current.source.incarnationId != incarnationId) return false
        byIdentity[sourceIdentity] = current.copy(fence = ExecutionFenceState.HARD_FENCED)
        return true
    }

    fun get(sourceIdentity: String): InstalledExecution? = byIdentity[sourceIdentity]

    fun isInstalledExecutable(sourceIdentity: String, incarnationId: Long): Boolean {
        val current = byIdentity[sourceIdentity] ?: return false
        return current.source.incarnationId == incarnationId && current.isExecutable
    }

    fun installedSnapshot(): Map<String, InstalledExecution> = byIdentity.toMap()

    /** Conference session wiring teardown — clears install registry after authority revoke. */
    fun clearForSessionWiring() {
        byIdentity.clear()
    }

    fun clear() {
        byIdentity.clear()
    }
}
