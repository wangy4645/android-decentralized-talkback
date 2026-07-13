package com.talkback.appprod.ui

/**
 * View-only presence display rules (ADR-0022 R27′-fix).
 * Driven by membership/connectivity divergence — not recovery attempt state.
 */
object MeetingPresenceDisplay {

    fun participantCountLabel(connectedCount: Int, joinedCount: Int): String =
        if (connectedCount == joinedCount) {
            joinedCount.toString()
        } else {
            "$connectedCount/$joinedCount"
        }
}
