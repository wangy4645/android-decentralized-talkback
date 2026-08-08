package com.talkback.core.session.membershipcontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0043 Seam I v0: O1 dispatch authorization (desk). */
class ConferenceRecoveryMembershipDispatchAuthorizerTest {

    private val authorizer = ConferenceRecoveryMembershipDispatchAuthorizer

    private fun query(
        channelId: String = "CH-01",
        decisionEpoch: Long = 7L,
        correlationId: String = "corr-7"
    ) = MembershipContextExistenceQuery(
        channelId = channelId,
        decisionEpoch = decisionEpoch,
        correlationId = correlationId,
        conferenceSessionId = "conf-88a94716"
    )

    private fun evidence(
        answer: MembershipContextExistenceAnswer,
        channelId: String = "CH-01",
        decisionEpoch: Long = 7L,
        correlationId: String = "corr-7",
        authorityId: String = "M01",
        authorityOriginated: Boolean = true
    ) = MembershipContextExistenceEvidence(
        answer = answer,
        channelId = channelId,
        decisionEpoch = decisionEpoch,
        correlationId = correlationId,
        authorityId = authorityId,
        authorityOriginated = authorityOriginated
    )

    @Test
    fun missingEvidence_unknownBlocksDispatch() {
        val result = authorizer.evaluate(
            query(),
            evidence(MembershipContextExistenceAnswer.UNKNOWN),
            expectedAuthorityId = "M01"
        )
        assertFalse(result.granted)
        assertEquals(MembershipDispatchBlockReason.EVIDENCE_UNKNOWN, result.blockReason)
    }

    private val expectedAuthorityId = "M01"

    @Test
    fun authorityAbsent_blocksDispatch() {
        val result = authorizer.evaluate(
            query(),
            evidence(MembershipContextExistenceAnswer.ABSENT),
            expectedAuthorityId = expectedAuthorityId
        )
        assertFalse(result.granted)
        assertEquals(MembershipDispatchBlockReason.EVIDENCE_ABSENT, result.blockReason)
    }

    @Test
    fun validPresent_f1f4Bound_grantsDispatch() {
        val result = authorizer.evaluate(
            query(),
            evidence(MembershipContextExistenceAnswer.PRESENT),
            expectedAuthorityId = expectedAuthorityId
        )
        assertTrue(result.granted)
    }

    @Test
    fun crossDecisionEpoch_presentReuseBlocked() {
        val result = authorizer.evaluate(
            query(decisionEpoch = 8L),
            evidence(MembershipContextExistenceAnswer.PRESENT, decisionEpoch = 7L),
            expectedAuthorityId = expectedAuthorityId
        )
        assertFalse(result.granted)
        assertEquals(MembershipDispatchBlockReason.EPOCH_MISMATCH, result.blockReason)
    }

    @Test
    fun scopeMismatch_presentBlockedToUnknown() {
        val result = authorizer.evaluate(
            query(channelId = "CH-01"),
            evidence(MembershipContextExistenceAnswer.PRESENT, channelId = "CH-02"),
            expectedAuthorityId = expectedAuthorityId
        )
        assertFalse(result.granted)
        assertEquals(MembershipDispatchBlockReason.SCOPE_MISMATCH, result.blockReason)
    }

    @Test
    fun correlationMismatch_blocksPresent() {
        val result = authorizer.evaluate(
            query(correlationId = "ask-a"),
            evidence(MembershipContextExistenceAnswer.PRESENT, correlationId = "ask-b"),
            expectedAuthorityId = expectedAuthorityId
        )
        assertFalse(result.granted)
        assertEquals(MembershipDispatchBlockReason.CORRELATION_MISMATCH, result.blockReason)
    }

    @Test
    fun ipPromotion_nonAuthorityOriginatedRejected() {
        val result = authorizer.evaluate(
            query(),
            evidence(
                MembershipContextExistenceAnswer.PRESENT,
                authorityOriginated = false
            ),
            expectedAuthorityId = expectedAuthorityId
        )
        assertFalse(result.granted)
        assertEquals(MembershipDispatchBlockReason.NOT_AUTHORITY_ORIGINATED, result.blockReason)
    }

    @Test
    fun presentNecessaryNotSufficient_o1MayDeny() {
        val result = authorizer.evaluate(
            query(),
            evidence(MembershipContextExistenceAnswer.PRESENT),
            expectedAuthorityId = expectedAuthorityId,
            supplementalDenyReason = "PEER_EDGE_NOT_READY"
        )
        assertFalse(result.granted)
        assertEquals(MembershipDispatchBlockReason.AUTHORIZATION_DENIED, result.blockReason)
    }

    @Test
    fun authorityMismatch_blocksPresent() {
        val result = authorizer.evaluate(
            query(),
            evidence(MembershipContextExistenceAnswer.PRESENT, authorityId = "M02"),
            expectedAuthorityId = expectedAuthorityId
        )
        assertFalse(result.granted)
        assertEquals(MembershipDispatchBlockReason.AUTHORITY_MISMATCH, result.blockReason)
    }
}
