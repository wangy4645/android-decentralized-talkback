package com.talkback.core.session.edge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeLinkLegacyGateProjectionTest {

    @Test
    fun shadowMode_neverOwns() {
        EdgeLinkState.entries.forEach { state ->
            assertFalse(
                EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(EdgeLinkMode.SHADOW, state),
            )
        }
    }

    @Test
    fun activeMode_ownsInFlightAndFailed_notIdleOrConnected() {
        val mode = EdgeLinkMode.ACTIVE_OFFERER
        assertFalse(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, null))
        assertFalse(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.IDLE))
        assertFalse(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.CONNECTED))
        assertTrue(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.OFFER_SENT))
        assertTrue(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.ICE_CHECKING))
        assertTrue(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.RESTARTING))
        assertTrue(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.REBUILDING))
        assertTrue(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.FAILED))
        assertTrue(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.AWAITING_OFFER))
        assertTrue(EdgeLinkLegacyGateProjection.controllerOwnsNegotiation(mode, EdgeLinkState.DEGRADED))
    }

    @Test
    fun onlyFailed_isExemptFromChannelTransmitGate() {
        assertTrue(EdgeLinkLegacyGateProjection.exemptFromChannelTransmitGate(EdgeLinkState.FAILED))
        assertFalse(EdgeLinkLegacyGateProjection.exemptFromChannelTransmitGate(EdgeLinkState.ICE_CHECKING))
        assertFalse(EdgeLinkLegacyGateProjection.exemptFromChannelTransmitGate(null))
    }
}
