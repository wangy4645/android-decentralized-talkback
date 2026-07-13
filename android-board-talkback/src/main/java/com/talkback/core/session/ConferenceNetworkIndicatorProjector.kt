package com.talkback.core.session

import com.talkback.core.qos.IceConnectivity

/**
 * Pure conference network-indicator projection (ADR-0022 R27′-A / #80).
 * v1 mapping is behavior-equivalent to the former [networkQualityLabel] ICE scan.
 * Intentionally does **not** filter recovering / edge-obligation edges (#81).
 */
object ConferenceNetworkIndicatorProjector {

    fun project(iceStates: Collection<String?>): ConferenceNetworkIndicator {
        if (iceStates.isEmpty()) return ConferenceNetworkIndicator.UNKNOWN
        if (iceStates.any { IceConnectivity.isLost(it) }) return ConferenceNetworkIndicator.DEGRADED
        if (iceStates.all { IceConnectivity.isConnected(it) }) return ConferenceNetworkIndicator.EXCELLENT
        return ConferenceNetworkIndicator.GOOD
    }
}
