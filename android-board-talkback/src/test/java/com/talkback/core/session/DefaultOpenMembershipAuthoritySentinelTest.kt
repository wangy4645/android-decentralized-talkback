package com.talkback.core.session

import com.talkback.core.util.RecoveryControlReconciliationMembershipObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** ADR-0022 E.18.1/E.18.2: default-open sentinel explicit Unwired outcome. */
class DefaultOpenMembershipAuthoritySentinelTest {

    @Test
    fun invariant_probe_returnsUnwired_notChecked() {
        val record = edgeRecord()
        val result = DefaultOpenMembershipAuthoritySentinel.probe(
            record = record,
            channelId = "CH-e18",
            conferenceSessionId = "sess-e18"
        )
        assertTrue(result is MembershipEpochProbeResult.Unwired)
        assertEquals("DEFAULT_OPEN_SENTINEL_NOT_WIRED", (result as MembershipEpochProbeResult.Unwired).reason)
    }

    @Test
    fun invariant_unwiredFact_includesAttemptAndGeneration() {
        val record = edgeRecord()
        val line = RecoveryControlReconciliationMembershipObservation.formatUnwired(
            record = record,
            channelId = "CH-e18",
            conferenceSessionId = "sess-e18",
            reason = "DEFAULT_OPEN_SENTINEL_NOT_WIRED"
        )
        assertTrue(line.startsWith("CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED"))
        assertTrue(line.contains("recoveryAttemptId=3"))
        assertTrue(line.contains("obligationGeneration=7"))
    }

    @Test
    fun currentBehavior_controllerDefaultProbe_emitsUnwiredAndDoesNotClaimChecked() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val logs = mutableListOf<String>()
        val controller = ConferenceEdgeRecoveryController(
            scheduler = scheduler,
            onLog = { logs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true }
        )
        try {
            controller.refreshControlReconciliationForTest(edgeRecord())
            assertTrue(logs.any { it.contains("CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED") })
            assertTrue(
                logs.any {
                    it.contains("RECOVERY_CONTROL_RECONCILIATION_FACT") &&
                        it.contains("membershipProbeDisposition=UNWIRED") &&
                        it.contains("membershipEpochConverged=false") &&
                        it.contains("reason=MEMBERSHIP_AUTHORITY_UNWIRED")
                }
            )
        } finally {
            controller.clearAll()
            scheduler.shutdownNow()
        }
    }

    private fun edgeRecord(): EdgeRecoveryRecord =
        EdgeRecoveryRecord(
            key = ConferenceEdgeKey("sess-e18", "M02"),
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            channelId = "CH-e18",
            recoveryAttemptId = 3L,
            recoveryStartedAtMs = 0L,
            obligationGeneration = 7L
        )
}