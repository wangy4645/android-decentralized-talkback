package com.talkback.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class IceRestartPrepareDecisionTest {
    @Test
    fun stableWithoutRemoteOnlyResetsAppFacts() {
        assertEquals(
            IceRestartPrepareDecision.Action.RESET_FACTS_ONLY,
            IceRestartPrepareDecision.evaluate("STABLE", null),
        )
    }

    @Test
    fun haveLocalOfferRollsBackEvenIfRemoteOfferRemains() {
        assertEquals(
            IceRestartPrepareDecision.Action.ROLLBACK_THEN_RESET,
            IceRestartPrepareDecision.evaluate("HAVE_LOCAL_OFFER", "OFFER"),
        )
    }

    @Test
    fun stableWithRemoteOfferIsIllegalRollback() {
        assertEquals(
            IceRestartPrepareDecision.Action.ABORT_ROLLBACK_ILLEGAL,
            IceRestartPrepareDecision.evaluate("STABLE", "OFFER"),
        )
    }

    @Test
    fun stableWithRemoteAnswerIsIllegalRollback() {
        assertEquals(
            IceRestartPrepareDecision.Action.ABORT_ROLLBACK_ILLEGAL,
            IceRestartPrepareDecision.evaluate("STABLE", "ANSWER"),
        )
    }
}
