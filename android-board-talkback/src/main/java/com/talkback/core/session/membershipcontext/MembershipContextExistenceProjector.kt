package com.talkback.core.session.membershipcontext

/**
 * ADR-0043 P1: obtain authority-grounded membership context existence evidence.
 * Implementations MUST NOT promote digest / topology / local session to PRESENT.
 */
interface MembershipContextExistenceProjector {
    fun obtainEvidence(query: MembershipContextExistenceQuery): MembershipContextExistenceEvidence

    /**
     * Request authority probe when evidence is UNKNOWN.
     * @return true if a probe was dispatched (or is already pending).
     */
    fun requestAuthorityProbe(query: MembershipContextExistenceQuery, authorityId: String): Boolean

    /** Cache authority response for subsequent authorization evaluation. */
    fun recordAuthorityResponse(evidence: MembershipContextExistenceEvidence)
}
