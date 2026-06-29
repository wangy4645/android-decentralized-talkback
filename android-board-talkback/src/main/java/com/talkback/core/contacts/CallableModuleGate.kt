package com.talkback.core.contacts

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks modules admitted to [Callable Roster] after Shared Secret verification
 * (gossip discovery or verified HELLO). NSD/static transport alone does not qualify.
 */
class CallableModuleGate {
    private val verifiedModuleIds = ConcurrentHashMap.newKeySet<String>()

    fun markVerified(moduleId: String) {
        if (moduleId.isNotBlank()) {
            verifiedModuleIds.add(moduleId)
        }
    }

    fun isVerified(moduleId: String): Boolean = verifiedModuleIds.contains(moduleId)

    fun verifiedModules(): Set<String> = verifiedModuleIds.toSet()

    fun clear() {
        verifiedModuleIds.clear()
    }
}
