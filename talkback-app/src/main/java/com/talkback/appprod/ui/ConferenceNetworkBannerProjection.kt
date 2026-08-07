package com.talkback.appprod.ui

import com.talkback.appprod.ui.UserVisibleConnectivityProjection.UserVisibleConnectivityState

/**
 * ADR-0025 presentation compliance (PR-A / P1A-PRES-001).
 *
 * Maps peer-level UVCP facts to conference banner scope. Does **not** read mesh ICE OR
 * aggregates ([ConferenceNetworkIndicatorProjector] remains diagnostics/QoS only).
 */
object ConferenceNetworkBannerProjection {

    enum class BannerScope {
        NONE,
        /** Self plane: local LAN / transport impairment. */
        LOCAL,
        /** Conference-wide: majority of participants impaired (not single-peer flap). */
        CONFERENCE
    }

    /**
     * @param peerConnectivity remote peers only (moduleId to UVCP state)
     * @param joinedParticipantCount membership size including local self
     */
    fun resolve(
        conferenceLive: Boolean,
        localLanOnline: Boolean,
        joinedParticipantCount: Int,
        peerConnectivity: List<Pair<String, UserVisibleConnectivityState>>
    ): BannerScope {
        if (!conferenceLive) return BannerScope.NONE
        if (!localLanOnline) return BannerScope.LOCAL

        val impairedPeers = peerConnectivity.filter { (_, state) ->
            UserVisibleConnectivityProjection.severity(state) > 0
        }
        if (impairedPeers.isEmpty()) return BannerScope.NONE

        val remotePeerCount = peerConnectivity.size
        if (remotePeerCount == 0) return BannerScope.NONE

        // Case C: N-1 or all remotes impaired; 2-party keeps peer hint only (threshold >= 2).
        val conferenceImpairmentThreshold = maxOf(2, joinedParticipantCount - 1)
        return if (impairedPeers.size >= conferenceImpairmentThreshold) {
            BannerScope.CONFERENCE
        } else {
            BannerScope.NONE
        }
    }
}