package com.talkback.core.session

import com.talkback.core.model.SignalType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0050 R2a — pure ingress readiness helpers. */
class NegotiationIngressGateTest {

    @Test
    fun helloAndHeartbeat_areNotNegotiationCapable() {
        assertFalse(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.HELLO))
        assertFalse(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.HEARTBEAT))
        assertFalse(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.DISCOVERY_PROBE))
        assertFalse(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.DISCOVERY_ANNOUNCE))
    }

    @Test
    fun webrtcAndMembershipSignals_areNegotiationCapable() {
        assertTrue(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.WEBRTC_OFFER))
        assertTrue(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.WEBRTC_ANSWER))
        assertTrue(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.WEBRTC_ICE))
        assertTrue(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.GROUP_RESYNC_REQUEST))
        assertTrue(NegotiationIngressGate.isNegotiationCapableSignal(SignalType.RECOVERY_REATTACH_ACK))
    }

    @Test
    fun isReady_requiresPostRecoveryFreshInbound() {
        assertFalse(
            NegotiationIngressGate.isReady(
                lastNegotiationCapableInboundAtMs = null,
                recoveryStartedAtMs = 1_000L,
                nowMs = 1_100L,
                freshMs = 5_000L
            )
        )
        assertFalse(
            NegotiationIngressGate.isReady(
                lastNegotiationCapableInboundAtMs = 900L,
                recoveryStartedAtMs = 1_000L,
                nowMs = 1_100L,
                freshMs = 5_000L
            )
        )
        assertTrue(
            NegotiationIngressGate.isReady(
                lastNegotiationCapableInboundAtMs = 1_050L,
                recoveryStartedAtMs = 1_000L,
                nowMs = 1_100L,
                freshMs = 5_000L
            )
        )
        assertFalse(
            NegotiationIngressGate.isReady(
                lastNegotiationCapableInboundAtMs = 1_050L,
                recoveryStartedAtMs = 1_000L,
                nowMs = 7_000L,
                freshMs = 5_000L
            )
        )
    }
}
