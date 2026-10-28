package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge

/**
 * Production-audible readiness for conference UI (orthogonal to ICE / group control).
 *
 * Cutover + catalog + local audible owner. Does not inspect Host ICE or roster epoch.
 */
data class ConferenceProductionMediaReadiness(
    val ready: Boolean,
    val block: String,
) {
    companion object {
        const val NONE = "NONE"
        const val SESSION_NOT_ACCEPTED = "SESSION_NOT_ACCEPTED"
        const val MULTICAST_SESSION_NOT_MATERIALIZED = "MULTICAST_SESSION_NOT_MATERIALIZED"
        const val CATALOG_EMPTY = "CATALOG_EMPTY"
        const val REMOTE_CATALOG_MEMBER_MISSING = "REMOTE_CATALOG_MEMBER_MISSING"
        const val AUDIBLE_OWNER_NOT_MULTICAST = "AUDIBLE_OWNER_NOT_MULTICAST"

        fun evaluate(
            sessionId: String,
            localModuleId: String,
            accepted: Boolean,
            isHost: Boolean,
        ): ConferenceProductionMediaReadiness {
            if (!accepted) {
                return ConferenceProductionMediaReadiness(false, SESSION_NOT_ACCEPTED)
            }
            if (!ConferenceSessionMediaBridge.hasSession(sessionId)) {
                return ConferenceProductionMediaReadiness(false, MULTICAST_SESSION_NOT_MATERIALIZED)
            }
            val catalog = ConferenceSessionMediaBridge.wiring?.catalog(sessionId)?.all().orEmpty()
            if (catalog.isEmpty()) {
                return ConferenceProductionMediaReadiness(false, CATALOG_EMPTY)
            }
            if (catalog.none { it.moduleId != localModuleId }) {
                return ConferenceProductionMediaReadiness(false, REMOTE_CATALOG_MEMBER_MISSING)
            }
            val owner = ReplacementCutoverRc1.currentState()
            // Host may still be ANCHOR_ACTIVE while inviting; participants require multicast owner.
            if (!isHost && owner != AudibleOwnershipState.MULTICAST_ACTIVE) {
                return ConferenceProductionMediaReadiness(false, AUDIBLE_OWNER_NOT_MULTICAST)
            }
            return ConferenceProductionMediaReadiness(true, NONE)
        }
    }
}
