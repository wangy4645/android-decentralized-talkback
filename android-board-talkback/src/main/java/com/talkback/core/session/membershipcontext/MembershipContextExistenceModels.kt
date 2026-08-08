package com.talkback.core.session.membershipcontext

/**
 * ADR-0043 P1: authority-grounded membership context existence answer.
 * UNKNOWN MUST NOT be interpreted as PRESENT or ABSENT.
 */
enum class MembershipContextExistenceAnswer {
    PRESENT,
    ABSENT,
    UNKNOWN
}

/** F4 scope + F1 decision epoch + correlation for a single authorization ask. */
data class MembershipContextExistenceQuery(
    val channelId: String,
    val decisionEpoch: Long,
    val correlationId: String,
    val conferenceSessionId: String
)

/**
 * ADR-0043 P1 evidence record.
 * [authorityOriginated] guards IP-001: digest / local session cannot produce PRESENT.
 */
data class MembershipContextExistenceEvidence(
    val answer: MembershipContextExistenceAnswer,
    val channelId: String,
    val decisionEpoch: Long,
    val correlationId: String,
    val authorityId: String,
    val authorityOriginated: Boolean
)

data class MembershipContextExistenceBindingOutcome(
    val effectiveAnswer: MembershipContextExistenceAnswer,
    val validForPresent: Boolean,
    val bindingFailure: MembershipDispatchBlockReason? = null
)

enum class MembershipDispatchBlockReason {
    EVIDENCE_UNKNOWN,
    EVIDENCE_ABSENT,
    SCOPE_MISMATCH,
    EPOCH_MISMATCH,
    CORRELATION_MISMATCH,
    NOT_AUTHORITY_ORIGINATED,
    AUTHORITY_MISMATCH,
    AUTHORIZATION_DENIED
}

data class MembershipDispatchAuthorizationResult(
    val granted: Boolean,
    val blockReason: MembershipDispatchBlockReason? = null,
    val shouldProbeAuthority: Boolean = false
)

/** F-MIN-001: PRESENT requires scope ∧ decision-epoch match at evaluation time. */
object MembershipContextExistenceEvidenceValidator {
    fun validateBindings(
        query: MembershipContextExistenceQuery,
        evidence: MembershipContextExistenceEvidence
    ): MembershipContextExistenceBindingOutcome {
        if (!evidence.authorityOriginated) {
            return MembershipContextExistenceBindingOutcome(
                effectiveAnswer = MembershipContextExistenceAnswer.UNKNOWN,
                validForPresent = false,
                bindingFailure = MembershipDispatchBlockReason.NOT_AUTHORITY_ORIGINATED
            )
        }
        if (evidence.correlationId != query.correlationId) {
            return MembershipContextExistenceBindingOutcome(
                effectiveAnswer = MembershipContextExistenceAnswer.UNKNOWN,
                validForPresent = false,
                bindingFailure = MembershipDispatchBlockReason.CORRELATION_MISMATCH
            )
        }
        if (evidence.channelId != query.channelId) {
            return MembershipContextExistenceBindingOutcome(
                effectiveAnswer = MembershipContextExistenceAnswer.UNKNOWN,
                validForPresent = false,
                bindingFailure = MembershipDispatchBlockReason.SCOPE_MISMATCH
            )
        }
        if (evidence.decisionEpoch != query.decisionEpoch) {
            return MembershipContextExistenceBindingOutcome(
                effectiveAnswer = MembershipContextExistenceAnswer.UNKNOWN,
                validForPresent = false,
                bindingFailure = MembershipDispatchBlockReason.EPOCH_MISMATCH
            )
        }
        val validForPresent = evidence.answer == MembershipContextExistenceAnswer.PRESENT
        return MembershipContextExistenceBindingOutcome(
            effectiveAnswer = evidence.answer,
            validForPresent = validForPresent
        )
    }
}

/**
 * ADR-0043 O1: issuer adjudicates GROUP_RESYNC dispatch within authorization rules.
 * PRESENT is necessary not sufficient (P1-AUTH-001).
 */
object ConferenceRecoveryMembershipDispatchAuthorizer {
    fun evaluate(
        query: MembershipContextExistenceQuery,
        evidence: MembershipContextExistenceEvidence,
        expectedAuthorityId: String,
        supplementalDenyReason: String? = null
    ): MembershipDispatchAuthorizationResult {
        if (evidence.authorityId != expectedAuthorityId) {
            return MembershipDispatchAuthorizationResult(
                granted = false,
                blockReason = MembershipDispatchBlockReason.AUTHORITY_MISMATCH,
                shouldProbeAuthority = true
            )
        }
        val binding = MembershipContextExistenceEvidenceValidator.validateBindings(query, evidence)
        if (binding.bindingFailure != null) {
            return MembershipDispatchAuthorizationResult(
                granted = false,
                blockReason = binding.bindingFailure,
                shouldProbeAuthority = binding.bindingFailure == MembershipDispatchBlockReason.NOT_AUTHORITY_ORIGINATED ||
                    evidence.answer == MembershipContextExistenceAnswer.UNKNOWN
            )
        }
        return when (binding.effectiveAnswer) {
            MembershipContextExistenceAnswer.UNKNOWN -> MembershipDispatchAuthorizationResult(
                granted = false,
                blockReason = MembershipDispatchBlockReason.EVIDENCE_UNKNOWN,
                shouldProbeAuthority = true
            )
            MembershipContextExistenceAnswer.ABSENT -> MembershipDispatchAuthorizationResult(
                granted = false,
                blockReason = MembershipDispatchBlockReason.EVIDENCE_ABSENT
            )
            MembershipContextExistenceAnswer.PRESENT -> {
                if (supplementalDenyReason != null) {
                    MembershipDispatchAuthorizationResult(
                        granted = false,
                        blockReason = MembershipDispatchBlockReason.AUTHORIZATION_DENIED
                    )
                } else {
                    MembershipDispatchAuthorizationResult(granted = true)
                }
            }
        }
    }

    fun correlationId(channelId: String, decisionEpoch: Long, conferenceSessionId: String): String =
        "$channelId:$decisionEpoch:$conferenceSessionId"
}
