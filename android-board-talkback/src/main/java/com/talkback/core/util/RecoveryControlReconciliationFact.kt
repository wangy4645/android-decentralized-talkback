package com.talkback.core.util

import com.talkback.core.session.ControlReconciliationFact
import com.talkback.core.session.EdgeRecoveryRecord

/** PR5-2b: observation-only control reconciliation fact (ADR-0022 Q6-2). */
object RecoveryControlReconciliationFact {

    private var testLogSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        testLogSink = sink
    }

    internal fun format(record: EdgeRecoveryRecord, fact: ControlReconciliationFact): String {
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
        val mismatch = fact.mismatchReason()
        if (mismatch != null) {
            sb.append(" reason=").append(mismatch)
        }
        return sb.toString()
    }

    internal fun emit(
        record: EdgeRecoveryRecord,
        fact: ControlReconciliationFact,
        overrideSink: ((String) -> Unit)? = null
    ) {
        val message = format(record, fact)
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