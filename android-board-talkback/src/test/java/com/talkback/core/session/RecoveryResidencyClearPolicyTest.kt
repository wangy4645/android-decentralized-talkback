package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-0045 Phase 1: [RecoveryResidencyClearPolicy] I1 invariants.
 *
 * Assertion discipline:
 * - **INVARIANT** — permanent contract; removal requires ADR-0045 amendment.
 */
class RecoveryResidencyClearPolicyTest {

    private val logs = mutableListOf<String>()

    private fun failedMediaClosed(
        phase: EdgeRecoveryPhase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY,
        attemptId: Long = 1L,
        obligationGeneration: Long = 1L,
        closeReason: ObligationCloseReason = ObligationCloseReason.OBLIGATION_DEADLINE
    ): EdgeRecoveryRecord {
        val key = ConferenceEdgeKey("sess-adr0045", "M03")
        return EdgeRecoveryRecord(
            key = key,
            phase = phase,
            channelId = "CH-1",
            recoveryAttemptId = attemptId,
            recoveryStartedAtMs = 0L,
            obligationGeneration = obligationGeneration,
            obligationOpenedAtMs = 10L,
            obligationClosedAtMs = 100L,
            obligationCloseReason = closeReason
        )
    }

    private fun host(
        record: EdgeRecoveryRecord,
        currentOverride: EdgeRecoveryRecord? = null
    ): RecoveryCompletionPolicy.MutationHost {
        val current = currentOverride ?: record
        return object : RecoveryCompletionPolicy.MutationHost {
            override fun currentRecord(key: ConferenceEdgeKey) = current
            override fun clock(): Long = 200L
            override fun log(message: String) {
                logs.add(message)
            }
            override fun cancelDebounce(key: ConferenceEdgeKey) = Unit
            override fun cancelWatchdog(key: ConferenceEdgeKey) = Unit
            override fun cancelDeadline(key: ConferenceEdgeKey) = Unit
            override fun logPhaseTransition(
                record: EdgeRecoveryRecord,
                oldPhase: EdgeRecoveryPhase,
                newPhase: EdgeRecoveryPhase,
                reason: String
            ) {
                logs.add("PHASE $oldPhase->$newPhase reason=$reason")
            }
            override fun expireDeferredIceRestartIntent(record: EdgeRecoveryRecord, reason: String) = Unit
            override fun notifyAttemptLineageObservation(record: EdgeRecoveryRecord, reason: String) = Unit
            override fun notifyChanged(sessionId: String) = Unit
            override fun logObligationCloseRequested(
                record: EdgeRecoveryRecord,
                reason: ObligationCloseReason,
                closeEvidence: String?
            ) = Unit
        }
    }

    private fun admit(
        record: EdgeRecoveryRecord,
        iceConnected: Boolean,
        receivePathLive: Boolean,
        currentOverride: EdgeRecoveryRecord? = null
    ): Boolean =
        RecoveryResidencyClearPolicy.clearFailedMediaResidencyPostObligation(
            host(record, currentOverride),
            record,
            iceConnected = iceConnected,
            receivePathLive = receivePathLive
        )

    /** INVARIANT N1: ICE alone must not clear residency. */
    @Test
    fun invariant_n1_iceAlone_doesNotClear() {
        logs.clear()
        val r = failedMediaClosed()
        assertFalse(admit(r, iceConnected = true, receivePathLive = false))
        assertEquals(EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY, r.phase)
        assertTrue(logs.any { it.contains("FAILED_MEDIA_RESIDENCY_CLEAR_HELD") && it.contains("e4_snapshot_unsatisfied") })
    }

    /** INVARIANT N1b: receivePathLive alone must not clear residency. */
    @Test
    fun invariant_n1b_receivePathAlone_doesNotClear() {
        logs.clear()
        val r = failedMediaClosed()
        assertFalse(admit(r, iceConnected = false, receivePathLive = true))
        assertEquals(EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY, r.phase)
    }

    /** INVARIANT N4: FAILED_REQUIRES_USER_ACTION must not be auto-cleared by E4. */
    @Test
    fun invariant_n4_userAction_notClearedByE4() {
        logs.clear()
        val r = failedMediaClosed(phase = EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION)
        assertFalse(admit(r, iceConnected = true, receivePathLive = true))
        assertEquals(EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION, r.phase)
        assertTrue(logs.any { it.contains("phase_not_failed_media_recovery") })
    }

    /** INVARIANT N3: markRecovered still rejects obligation_already_closed (must not relax). */
    @Test
    fun invariant_n3_markRecovered_stillRejectsClosedObligation() {
        logs.clear()
        val r = failedMediaClosed()
        val applied = RecoveryCompletionPolicy.markRecovered(host(r), r, "ICE_CONNECTED")
        assertFalse(applied)
        assertEquals(EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY, r.phase)
        assertTrue(logs.any { it.contains("obligation_already_closed") })
    }

    /** INVARIANT P1 + P1.a + N5: GATE∧E4 → CONNECTED, never RECOVERED; closeReason preserved; no completion event. */
    @Test
    fun invariant_p1_gateAndE4_clearsToConnected_neverRecovered() {
        logs.clear()
        val r = failedMediaClosed()
        assertTrue(admit(r, iceConnected = true, receivePathLive = true))
        assertEquals(EdgeRecoveryPhase.CONNECTED, r.phase)
        assertEquals(ObligationCloseReason.OBLIGATION_DEADLINE, r.obligationCloseReason)
        assertEquals(100L, r.obligationClosedAtMs)
        assertTrue(logs.any { it.contains("FAILED_MEDIA_RESIDENCY_CLEARED") && it.contains("writer=ResidencyClearPolicy") })
        assertFalse(logs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertFalse(logs.any { it.contains("RECOVERY_OBLIGATION_CLOSED") && it.contains("RECOVERED") })
        assertTrue(logs.any { it.contains("PHASE FAILED_MEDIA_RECOVERY->CONNECTED") })
    }

    /** INVARIANT: obligation still open blocks clear (GATE). */
    @Test
    fun invariant_obligationOpen_holdsClear() {
        logs.clear()
        val r = failedMediaClosed().also {
            it.obligationClosedAtMs = null
            it.obligationCloseReason = null
        }
        assertFalse(admit(r, iceConnected = true, receivePathLive = true))
        assertEquals(EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY, r.phase)
        assertTrue(logs.any { it.contains("obligation_still_open") })
    }

    /** INVARIANT N2 / writer provenance: clear API lives only on ResidencyClearPolicy. */
    @Test
    fun invariant_n2_clearWriterOnlyInResidencyClearPolicy() {
        val sessionDir = File("src/main/java/com/talkback/core/session")
        val offenders = sessionDir.listFiles { f ->
            f.extension == "kt" && f.name != "RecoveryResidencyClearPolicy.kt"
        }?.filter { file ->
            file.readText().contains("fun clearFailedMediaResidencyPostObligation")
        } ?: emptyList()
        assertTrue(
            "clearFailedMediaResidencyPostObligation outside ClearPolicy: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    /** INVARIANT: stale lineage must not clear. */
    @Test
    fun invariant_staleLineage_holdsClear() {
        logs.clear()
        val r = failedMediaClosed(attemptId = 1L, obligationGeneration = 1L)
        val current = failedMediaClosed(attemptId = 2L, obligationGeneration = 1L)
        assertFalse(admit(r, iceConnected = true, receivePathLive = true, currentOverride = current))
        assertEquals(EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY, r.phase)
        assertTrue(logs.any { it.contains("stale_lineage") })
    }

    /** INVARIANT: terminal phase-mutation writers are CompletionPolicy + ResidencyClearPolicy only. */
    @Test
    fun invariant_terminalPhaseWriters_policyFamilyOnly() {
        val sessionDir = File("src/main/java/com/talkback/core/session")
        val allowed = setOf("RecoveryCompletionPolicy.kt", "RecoveryResidencyClearPolicy.kt")
        val offenders = sessionDir.listFiles { f ->
            f.extension == "kt" && f.name !in allowed
        }?.filter { file ->
            val text = file.readText()
            text.contains("fun markRecovered") ||
                text.contains("fun closeObligation") ||
                text.contains("fun markFailedFinal") ||
                text.contains("fun clearFailedMediaResidencyPostObligation")
        } ?: emptyList()
        assertTrue("Terminal phase writers outside policy family: ${offenders.map { it.name }}", offenders.isEmpty())
        assertNull(
            "ClearPolicy must not define markRecovered",
            File("src/main/java/com/talkback/core/session/RecoveryResidencyClearPolicy.kt")
                .readText()
                .takeIf { it.contains("fun markRecovered") }
        )
    }
}
