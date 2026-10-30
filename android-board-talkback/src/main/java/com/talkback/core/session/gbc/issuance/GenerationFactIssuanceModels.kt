package com.talkback.core.session.gbc.issuance

/**
 * ADR-0057 PV-2 origin issuance commitment models (contract-frozen semantics).
 */
data class GenerationFactIssuanceKey(
    val moduleId: String,
    val signerKeyVersion: Long,
) {
    fun storageId(): String = "${moduleId}#${signerKeyVersion}"
}

enum class IssuanceRetirementPhase {
    ACTIVE,
    RETIREMENT_PREPARE,
    TERMINAL_HEAD_PROPOSED,
}

data class PreparedIssuanceRecord(
    val prepareId: String,
    val factCommitment: ByteArray,
    val authorityCanonicalBytes: ByteArray,
    val verificationContextBytes: ByteArray,
    val admittedBeforeFence: Boolean,
)

data class FinalizedIssuanceRecord(
    val factCommitment: ByteArray,
    val signedFactBytes: ByteArray,
    val merkleIndex: Int,
)

data class ProposedTerminalHead(
    val moduleId: String,
    val signerKeyVersion: Long,
    val issuanceRoot: ByteArray,
    val finalizedCount: Int,
)

sealed class PrepareIssuanceResult {
    data class Prepared(
        val prepareId: String,
        val factCommitment: ByteArray,
    ) : PrepareIssuanceResult()

    data object FenceClosed : PrepareIssuanceResult()

    data object Retired : PrepareIssuanceResult()
}

sealed class FinalizeIssuanceResult {
    data class Finalized(
        val record: FinalizedIssuanceRecord,
        val idempotentReplay: Boolean,
    ) : FinalizeIssuanceResult()

    data object PrepareNotFound : FinalizeIssuanceResult()

    data object FenceRejected : FinalizeIssuanceResult()

    data object Retired : FinalizeIssuanceResult()

    data class IntegrityConflict(
        val message: String,
    ) : FinalizeIssuanceResult()
}

sealed class PublishAdmissionResult {
    data class Admitted(
        val signedFactBytes: ByteArray,
        val factCommitment: ByteArray,
    ) : PublishAdmissionResult()

    data object NotFinalized : PublishAdmissionResult()

    data object Retired : PublishAdmissionResult()
}

sealed class RetirementPrepareResult {
    data object Started : RetirementPrepareResult()

    data object AlreadyPrepared : RetirementPrepareResult()

    data object AlreadyProposed : RetirementPrepareResult()
}

sealed class TerminalHeadResult {
    data class Proposed(
        val head: ProposedTerminalHead,
    ) : TerminalHeadResult()

    data object NotPrepared : TerminalHeadResult()

    data object UnresolvedPrepared : TerminalHeadResult()
}
