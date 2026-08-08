package com.talkback.core.session.membershipcontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0043 F-MIN-001: F1 epoch + F4 scope binding at evaluation time. */
class MembershipContextExistenceEvidenceValidatorTest {

    private val validator = MembershipContextExistenceEvidenceValidator

    private fun query(epoch: Long = 3L, channel: String = "CH-01", corr: String = "c1") =
        MembershipContextExistenceQuery(
            channelId = channel,
            decisionEpoch = epoch,
            correlationId = corr,
            conferenceSessionId = "sess-conf"
        )

    private fun evidence(
        answer: MembershipContextExistenceAnswer = MembershipContextExistenceAnswer.PRESENT,
        epoch: Long = 3L,
        channel: String = "CH-01",
        corr: String = "c1",
        authorityOriginated: Boolean = true
    ) = MembershipContextExistenceEvidence(
        answer = answer,
        channelId = channel,
        decisionEpoch = epoch,
        correlationId = corr,
        authorityId = "M01",
        authorityOriginated = authorityOriginated
    )

    @Test
    fun presentWithBindings_validForAuthorization() {
        val outcome = validator.validateBindings(query(), evidence())
        assertTrue(outcome.validForPresent)
        assertEquals(MembershipContextExistenceAnswer.PRESENT, outcome.effectiveAnswer)
    }

    @Test
    fun digestStylePromotion_withoutAuthorityOrigin_notPresent() {
        val outcome = validator.validateBindings(
            query(),
            evidence(authorityOriginated = false)
        )
        assertFalse(outcome.validForPresent)
        assertEquals(MembershipContextExistenceAnswer.UNKNOWN, outcome.effectiveAnswer)
    }

    @Test
    fun staleEpoch_invalidatesPresentToUnknown() {
        val outcome = validator.validateBindings(
            query(epoch = 4L),
            evidence(epoch = 3L)
        )
        assertFalse(outcome.validForPresent)
        assertEquals(MembershipContextExistenceAnswer.UNKNOWN, outcome.effectiveAnswer)
    }
}
