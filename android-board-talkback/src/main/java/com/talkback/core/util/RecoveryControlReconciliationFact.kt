package com.talkback.core.util

import com.talkback.core.session.ControlReconciliationFact
import com.talkback.core.session.EdgeRecoveryRecord

/** PR5-2b: observation-only control reconciliation fact (ADR-0022 Q6-2). */
object RecoveryControlReconciliationFact {

    private var testLogSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        testLogSink = sink
    }

    /** Optional digest provenance attached on DIGEST_REFRESH re-evaluations (ADR-0036 Fix-D). */
    data class DigestRefreshAudit(
        val oldDigestEpoch: Long?,
        val oldDigestHash: Int?,
        val newDigestEpoch: Long?,
        val newDigestHash: Int?
    )

    internal fun format(
        record: EdgeRecoveryRecord,
        fact: ControlReconciliationFact,
        digestAudit: DigestRefreshAudit? = null
    ): String {
        val key = record.key
        val sb = StringBuilder("RECOVERY_CONTROL_RECONCILIATION_FACT")
        sb.append(" session=").append(key.sessionId)
        sb.append(" remote=").append(key.remoteModuleId)
        sb.append(" episodeId=").append(fact.obligationGeneration)
        sb.append(" recoveryAttemptId=").append(fact.attemptId)
        sb.append(" obligationGeneration=").append(fact.obligationGeneration)
        sb.append(" controlHandshakeCompleted=").append(fact.controlHandshakeCompleted)
        sb.append(" sessionEpochMatched=").append(fact.sessionEpochMatched)
        sb.append(" membershipEpochConverged=").append(fact.membershipEpochConverged)
        sb.append(" membershipProbeDisposition=").append(fact.membershipProbeDisposition)
        sb.append(" result=").append(fact.result)
        if (digestAudit != null) {
            sb.append(" oldDigestEpoch=").append(digestAudit.oldDigestEpoch ?: "null")
            sb.append(" oldDigestHash=").append(digestAudit.oldDigestHash ?: "null")
            sb.append(" newDigestEpoch=").append(digestAudit.newDigestEpoch ?: "null")
            sb.append(" newDigestHash=").append(digestAudit.newDigestHash ?: "null")
        }
        val mismatch = fact.mismatchReason()
        if (mismatch != null) {
            sb.append(" reason=").append(mismatch)
        }
        return sb.toString()
    }

    internal fun emit(
        record: EdgeRecoveryRecord,
        fact: ControlReconciliationFact,
        overrideSink: ((String) -> Unit)? = null,
        digestAudit: DigestRefreshAudit? = null
    ) {
        val message = format(record, fact, digestAudit)
        if (overrideSink != null) {
            overrideSink(message)
            return
        }
        val sink = testLogSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }
}