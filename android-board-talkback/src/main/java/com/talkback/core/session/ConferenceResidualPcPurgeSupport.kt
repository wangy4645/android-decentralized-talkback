package com.talkback.core.session

/**
 * OPS-07: guard [purgeResidualConferencePeerConnections] from releasing a legitimate
 * outstanding offerer negotiation (handoff active + host-local offer lineage).
 */
object ConferenceResidualPcPurgeSupport {

    fun isProtectedOutstandingNegotiation(
        stuckOfferer: Boolean,
        admissionHandoffActive: Boolean,
        hostOutstandingOfferLineageId: String?,
    ): Boolean {
        if (!stuckOfferer || !admissionHandoffActive) return false
        val lineage = hostOutstandingOfferLineageId?.takeIf {
            it.isNotBlank() && it != ConferenceRealizationLineage.UNKNOWN
        }
        return lineage != null
    }
}
