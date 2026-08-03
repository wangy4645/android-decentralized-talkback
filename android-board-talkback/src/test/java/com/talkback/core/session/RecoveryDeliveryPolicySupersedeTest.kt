package com.talkback.core.session

import com.talkback.core.util.RecoveryDeliveryFact
import com.talkback.core.util.RecoveryIngressObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/** ADR-0022 §E.17 Grill R3: recovery delivery obligation conservation on supersede. */
class RecoveryDeliveryPolicySupersedeTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val policyLogs = mutableListOf<String>()
    private val factLines = mutableListOf<String>()
    private lateinit var policy: RecoveryOfferDeliveryPolicy
    private lateinit var record: EdgeRecoveryRecord

    private val sessionId = "sess-1"
    private val remoteModuleId = "M03"
    private val lineageId = "L1"

    @Before
    fun setUp() {
        policyLogs.clear()
        factLines.clear()
        RecoveryDeliveryFact.resetForTest { factLines.add(it) }
        RecoveryIngressObservation.resetForTest(deadlineMs = 5_000L)
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
        record = pendingRecord()
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

    private fun pendingRecord(
        deliveryAttemptId: Long = 1L,
        lineage: String = lineageId
    ): EdgeRecoveryRecord {
        return EdgeRecoveryRecord(
            key = ConferenceEdgeKey(sessionId, remoteModuleId),
            phase = EdgeRecoveryPhase.REATTACH_ACCEPTED,
            channelId = "CH-1",
            recoveryAttemptId = 1L,
            recoveryStartedAtMs = 0L,
            recoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.PENDING,
            recoveryOfferLineageId = lineage,
            recoveryOfferDeliveryAttemptId = deliveryAttemptId,
            recoveryOfferLastDispatchAtMs = 0L
        )
    }

    private fun identity(deliveryAttemptId: Long = 1L, lineage: String = lineageId) =
        RecoveryDeliveryFact.Identity(
            offerLineageId = lineage,
            recoveryAttemptId = 1L,
            obligationGeneration = 1L,
            deliveryAttemptId = deliveryAttemptId,
            from = "M02",
            to = remoteModuleId
        )

    private fun absentCount(): Int =
        factLines.count { it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") }

    @Test
    fun supersedeLineage_closesObservationWindow() {
        val id = identity()
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, sessionId)

        policy.supersedeLineage(record, "REATTACH_INBOUND")

        assertEquals(RecoveryOfferDeliveryPhase.SUPERSEDED, record.recoveryOfferDeliveryPhase)
        assertTrue(
            factLines.any {
                it.startsWith("RECOVERY_DELIVERY_LINEAGE_SUPERSEDED") &&
                    it.contains("reason=REATTACH_INBOUND")
            }
        )

        RecoveryIngressObservation.fireWindowDeadlineForTest(id, sessionId)
        assertEquals(0, absentCount())

        RecoveryDeliveryFact.emitRemoteIngressAbsent(id, sessionId)
        assertTrue(policyLogs.none { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") })
        assertFalse(
            factLines.any {
                it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") &&
                    it.contains("recoveryAttemptId=0") &&
                    it.contains("obligationGeneration=0")
            }
        )
    }

    @Test
    fun supersededLineage_isDistinguishableFromNeverCreated() {
        val neverCreated = EdgeRecoveryRecord(
            key = ConferenceEdgeKey(sessionId, remoteModuleId),
            phase = EdgeRecoveryPhase.RECOVERY_PENDING,
            channelId = "CH-1",
            recoveryAttemptId = 2L,
            recoveryStartedAtMs = 0L
        )
        assertEquals(RecoveryOfferDeliveryPhase.NONE, neverCreated.recoveryOfferDeliveryPhase)
        assertEquals(null, neverCreated.recoveryOfferLineageId)

        RecoveryDeliveryFact.emit(
            RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED,
            identity(),
            sessionId
        )
        policy.supersedeLineage(record, "REATTACH_INBOUND")

        assertEquals(RecoveryOfferDeliveryPhase.SUPERSEDED, record.recoveryOfferDeliveryPhase)
        assertEquals(lineageId, record.recoveryOfferLineageId)
        assertNotEquals(neverCreated.recoveryOfferDeliveryPhase, record.recoveryOfferDeliveryPhase)
        assertTrue(record.recoveryOfferDeliveryPhase.isTerminal())
        assertFalse(neverCreated.recoveryOfferDeliveryPhase.isTerminal())
    }
}
