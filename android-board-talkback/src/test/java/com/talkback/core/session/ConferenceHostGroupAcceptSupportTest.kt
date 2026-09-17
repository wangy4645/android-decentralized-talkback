package com.talkback.core.session

import com.talkback.core.session.ConferenceHostGroupAcceptSupport.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ConferenceHostGroupAcceptSupportTest {

    @Test
    fun matchWithEngine_applies() {
        assertEquals(
            Action.APPLY_TO_EXISTING_ENGINE,
            ConferenceHostGroupAcceptSupport.resolveAction(
                ConferenceRealizationLineage.Correlation.MATCH,
                enginePresent = true,
            )
        )
    }

    @Test
    fun matchWithoutEngine_failClosed() {
        assertEquals(
            Action.FAIL_CLOSED_RECOVERY,
            ConferenceHostGroupAcceptSupport.resolveAction(
                ConferenceRealizationLineage.Correlation.MATCH,
                enginePresent = false,
            )
        )
    }

    @Test
    fun mismatch_failClosedWithoutApply() {
        assertEquals(
            Action.FAIL_CLOSED_RECOVERY,
            ConferenceHostGroupAcceptSupport.resolveAction(
                ConferenceRealizationLineage.Correlation.MISMATCH,
                enginePresent = true,
            )
        )
    }

    @Test
    fun unknownLineage_failClosed() {
        assertEquals(
            Action.FAIL_CLOSED_RECOVERY,
            ConferenceHostGroupAcceptSupport.resolveAction(
                ConferenceRealizationLineage.Correlation.OFFER_LINEAGE_UNKNOWN,
                enginePresent = true,
            )
        )
    }
}
