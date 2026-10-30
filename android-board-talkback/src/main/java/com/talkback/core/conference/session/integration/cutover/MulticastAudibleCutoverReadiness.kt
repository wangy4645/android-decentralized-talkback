package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.integration.Profile01ShadowMulticastReceiveSeam
import com.talkback.core.conference.session.integration.Profile01ShadowPlayoutClockSeam

/**
 * R1 — readiness projection for GA default audible cutover.
 * Composes existing session/shadow facts only; no timers or ingress heuristics.
 */
data class MulticastAudibleCutoverReadiness(
    val ready: Boolean,
    val missing: List<String>,
) {
    companion object {
        fun evaluate(
            sessionId: String,
            localModuleId: String,
            playoutSeam: Profile01ShadowPlayoutClockSeam? =
                ConferenceSessionMediaCoordinatorDelegate.profile01ShadowPlayoutClockSeam,
            receiveSeam: Profile01ShadowMulticastReceiveSeam? =
                ConferenceSessionMediaCoordinatorDelegate.profile01ShadowReceiveSeam,
        ): MulticastAudibleCutoverReadiness {
            val missing = mutableListOf<String>()
            val wiring = ConferenceSessionMediaBridge.wiring

            if (wiring == null) {
                missing += "WIRING_NOT_CONFIGURED"
            }
            if (!ConferenceSessionMediaBridge.hasSession(sessionId)) {
                missing += "MULTICAST_SESSION_NOT_MATERIALIZED"
            }
            if (wiring != null && wiring.isIngressBlocked(sessionId)) {
                missing += "SESSION_NOT_OPERATIONAL"
            }
            if (ConferenceSessionMediaBridge.currentIncarnation(sessionId, localModuleId) == null) {
                missing += "LOCAL_TX_BINDING_NOT_READY"
            }
            if (playoutSeam?.isArmed(sessionId) != true) {
                missing += "AUDIBLE_PLAYOUT_PATH_NOT_READY"
            }
            if (receiveSeam?.isArmed(sessionId) != true) {
                missing += "MULTICAST_RECEIVE_PATH_NOT_READY"
            }
            if (!hasRemoteMulticastSourceBinding(sessionId, localModuleId)) {
                missing += "REMOTE_MULTICAST_RX_SOURCE_NOT_READY"
            }
            val ownership = ReplacementCutoverRc1.currentState()
            if (ownership != null && ownership != AudibleOwnershipState.ANCHOR_ACTIVE) {
                missing += "OWNERSHIP_NOT_ANCHOR_ACTIVE"
            }

            return MulticastAudibleCutoverReadiness(
                ready = missing.isEmpty(),
                missing = missing,
            )
        }

        /**
         * Structural remote receive capability: a non-local [SourceBindingCatalog] entry,
         * same catalog authority already used for LOCAL_TX_BINDING_NOT_READY.
         */
        private fun hasRemoteMulticastSourceBinding(
            sessionId: String,
            localModuleId: String,
        ): Boolean {
            val bindings = ConferenceSessionMediaBridge.wiring?.catalog(sessionId)?.all() ?: return false
            return bindings.any { it.moduleId != localModuleId }
        }
    }
}
