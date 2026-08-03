package com.talkback.core.session

import com.talkback.core.util.RecoveryDeliveryFact
import com.talkback.core.util.RecoveryIngressObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * P1 Attempt-4b obligation-layer replay (ADR-0022 E.17).
 *
 * Replays the supersede topology from logs/phase3c-b-attempt4b-20260802-220150:
 * session e79b1f7a, edge M02->M03, lineage L1, attempt 1 superseded by REATTACH_INBOUND.
 */
class Attempt4bR3ReplayTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val policyLogs = mutableListOf<String>()
    private val factLines = mutableListOf<String>()
    private val observationLines = mutableListOf<String>()
    private lateinit var policy: RecoveryOfferDeliveryPolicy
    private lateinit var record: EdgeRecoveryRecord

    private val sessionId = "e79b1f7a-3a2e-41c9-a2dd-b910a7c971f2"
    private val remoteModuleId = "M03"
    private val lineageId = "L1"

    @Before
    fun setUp() {
        policyLogs.clear()
        factLines.clear()
        observationLines.clear()
        RecoveryDeliveryFact.resetForTest { factLines.add(it) }
        RecoveryIngressObservation.resetForTest(
            deadlineMs = 3_000L,
            observationLog = { observationLines.add(it) }
        )
        policy = RecoveryOfferDeliveryPolicy(
            localModuleId = "M02",
            maxDeliveryAttempts = 3,
            deliveryRetryIntervalMs = 200L,
            deliveryRetryMinGapMs = 0L,
            clock = { 0L },
            scheduler = scheduler,
            onLog = { policyLogs.add(it) },
            onDispatchRecoveryOffer = { _, _, _, _ -> true },
            canDispatchRecoverySignal = { _, _ -> true }
        )
        record = episodeRecord()
        RecoveryDeliveryFact.bindIngressAbsentHandler { identity, sid ->
            policy.onRemoteIngressAbsent(record, identity, sid)
        }
    }

    @After
    fun tearDown() {
        RecoveryIngressObservation.shutdownForTest()
        RecoveryDeliveryFact.resetForTest()
        scheduler.shutdownNow()
    }

    private fun episodeRecord(): EdgeRecoveryRecord {
        return EdgeRecoveryRecord(
            key = ConferenceEdgeKey(sessionId, remoteModuleId),
            phase = EdgeRecoveryPhase.REATTACH_ACCEPTED,
            channelId = "CH-1",
            recoveryAttemptId = 1L,
            recoveryStartedAtMs = 0L,
            obligationGeneration = 1L,
            recoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.PENDING,
            recoveryOfferLineageId = lineageId,
            recoveryOfferDeliveryAttemptId = 1L,
            recoveryOfferLastDispatchAtMs = 0L
        )
    }

    private fun episodeIdentity() = RecoveryDeliveryFact.Identity(
        offerLineageId = lineageId,
        recoveryAttemptId = 1L,
        obligationGeneration = 1L,
        deliveryAttemptId = 1L,
        from = "M02",
        to = remoteModuleId
    )

    private fun phantomAbsentCount(): Int =
        factLines.count {
            it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") &&
                it.contains("recoveryAttemptId=0") &&
                it.contains("obligationGeneration=0")
        }

    @Test
    fun attempt4bR3Replay_supersedeClosesObligationWithoutPhantomAbsent() {
        val id = episodeIdentity()

        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, sessionId)
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.DELIVERY_PENDING, id, sessionId)

        policy.supersedeLineage(record, "REATTACH_INBOUND")

        assertTrue(
            factLines.any {
                it.startsWith("RECOVERY_DELIVERY_LINEAGE_SUPERSEDED") &&
                    it.contains("offerLineageId=L1") &&
                    it.contains("recoveryAttemptId=1") &&
                    it.contains("reason=REATTACH_INBOUND")
            }
        )
        assertTrue(
            observationLines.any {
                it.contains("RECOVERY_INGRESS_WINDOW_CLOSED") &&
                    it.contains("offerLineageId=L1") &&
                    it.contains("state=CLOSED_SUPERSEDED")
            }
        )
        assertEquals(RecoveryOfferDeliveryPhase.SUPERSEDED, record.recoveryOfferDeliveryPhase)

        RecoveryIngressObservation.fireWindowDeadlineForTest(id, sessionId)
        assertEquals(0, phantomAbsentCount())
        assertFalse(factLines.any { it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") })
        assertTrue(policyLogs.none { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") })
    }
}
