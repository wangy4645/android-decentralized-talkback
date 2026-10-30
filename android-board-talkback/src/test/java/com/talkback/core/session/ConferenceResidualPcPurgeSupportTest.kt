package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceResidualPcPurgeSupportTest {

    @Test
    fun protected_whenHandoffActiveStuckOffererAndLineagePresent() {
        assertTrue(
            ConferenceResidualPcPurgeSupport.isProtectedOutstandingNegotiation(
                stuckOfferer = true,
                admissionHandoffActive = true,
                hostOutstandingOfferLineageId = "CR2",
            )
        )
    }

    @Test
    fun notProtected_withoutHandoff_evenWithLineage() {
        assertFalse(
            ConferenceResidualPcPurgeSupport.isProtectedOutstandingNegotiation(
                stuckOfferer = true,
                admissionHandoffActive = false,
                hostOutstandingOfferLineageId = "CR2",
            )
        )
    }

    @Test
    fun notProtected_withoutStuckOfferer_evenWithHandoffAndLineage() {
        assertFalse(
            ConferenceResidualPcPurgeSupport.isProtectedOutstandingNegotiation(
                stuckOfferer = false,
                admissionHandoffActive = true,
                hostOutstandingOfferLineageId = "CR2",
            )
        )
    }

    @Test
    fun notProtected_whenLineageAbsentOrUnknown() {
        assertFalse(
            ConferenceResidualPcPurgeSupport.isProtectedOutstandingNegotiation(
                stuckOfferer = true,
                admissionHandoffActive = true,
                hostOutstandingOfferLineageId = null,
            )
        )
        assertFalse(
            ConferenceResidualPcPurgeSupport.isProtectedOutstandingNegotiation(
                stuckOfferer = true,
                admissionHandoffActive = true,
                hostOutstandingOfferLineageId = ConferenceRealizationLineage.UNKNOWN,
            )
        )
    }

    /** Residual cleanup must still purge true stale PCs (no active handoff). */
    @Test
    fun residualStuckOffererWithoutHandoff_isNotProtected() {
        assertFalse(
            ConferenceResidualPcPurgeSupport.isProtectedOutstandingNegotiation(
                stuckOfferer = true,
                admissionHandoffActive = false,
                hostOutstandingOfferLineageId = null,
            )
        )
    }
}
