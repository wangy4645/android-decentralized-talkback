package com.talkback.core.session.edge

/**
 * Phase 3: legacy GIDR / L0 / mesh-join clocks are projections of [EdgeLinkState], not peers of it.
 * When the controller already owns an in-flight negotiation, those paths must not mint a new lineage.
 */
object EdgeLinkLegacyGateProjection {

    fun controllerOwnsNegotiation(mode: EdgeLinkMode, state: EdgeLinkState?): Boolean {
        if (mode == EdgeLinkMode.SHADOW || state == null) return false
        return when (state) {
            EdgeLinkState.OFFER_SENT,
            EdgeLinkState.AWAITING_OFFER,
            EdgeLinkState.ICE_CHECKING,
            EdgeLinkState.DEGRADED,
            EdgeLinkState.RESTARTING,
            EdgeLinkState.REBUILDING,
            EdgeLinkState.FAILED -> true
            EdgeLinkState.IDLE,
            EdgeLinkState.CONNECTED -> false
        }
    }

    /** FAILED edges must not hold the channel in BUILDING. */
    fun exemptFromChannelTransmitGate(state: EdgeLinkState?): Boolean =
        state == EdgeLinkState.FAILED
}
