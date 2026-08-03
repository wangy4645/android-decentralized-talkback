package com.talkback.core.session

import com.talkback.core.util.RecoveryDeliveryFact
import com.talkback.core.util.RecoveryIngressObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/** D1 Slice-2A: ABSENT(window) consumed by RecoveryOfferDeliveryPolicy.evaluateRetry. */
class RecoveryDeliveryPolicyRetryTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val policyLogs = mutableListOf<String>()
    private val factLines = mutableListOf<String>()
    private var dispatchCalls = 0
    private lateinit var policy: RecoveryOfferDeliveryPolicy
    private lateinit var record: EdgeRecoveryRecord

    private val sessionId = "sess-1"
    private val remoteModuleId = "M03"
    private val lineageId = "L1"

    @Before
    fun setUp() {
        policyLogs.clear()
        factLines.clear()
        dispatchCalls = 0
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
            onDispatchRecoveryOffer = { _, _, _, _ ->
                dispatchCalls++
                true
            },
            canDispatchRecoverySignal = { _, _ -> true }
        )
        record = pendingRecord(deliveryAttemptId = 1L)
        RecoveryDeliveryFact.bindIngressAbsentHandler { identity, sessionId ->
            policy.onRemoteIngressAbsent(record, identity, sessionId)
        }
    }

    @After
    fun tearDown() {
        RecoveryIngressObservation.shutdownForTest()
        RecoveryDeliveryFact.resetForTest()
        scheduler.shutdownNow()
    }

    private fun pendingRecord(
        deliveryAttemptId: Long,
        lineage: String = lineageId
    ): EdgeRecoveryRecord {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        return EdgeRecoveryRecord(
            key = key,
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

    private fun evaluateLogs(): List<String> =
        policyLogs.filter { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") }

    @Test
    fun absentFact_invokesPolicyEvaluateRetry() {
        RecoveryDeliveryFact.emitRemoteIngressAbsent(identity(), sessionId)
        assertEquals(1, evaluateLogs().size)
        assertTrue(evaluateLogs().single().contains("trigger=REMOTE_INGRESS_ABSENT"))
        assertTrue(evaluateLogs().single().contains("deliveryAttemptId=1"))
        assertTrue(factLines.any { it.startsWith("RECOVERY_DELIVERY_RETRY_ADMITTED") })
        assertEquals(1, dispatchCalls)
    }

    @Test
    fun absentDuplicate_evaluatesOnce() {
        val id = identity()
        RecoveryDeliveryFact.emitRemoteIngressAbsent(id, sessionId)
        RecoveryDeliveryFact.emitRemoteIngressAbsent(id, sessionId)
        assertEquals(1, evaluateLogs().size)
        assertEquals(1, dispatchCalls)
    }

    @Test
    fun ingressWindowDeadline_wiresAbsentToPolicy() {
        val id = identity()
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, sessionId)
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, sessionId)
        assertTrue(factLines.any { it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") })
        assertEquals(1, evaluateLogs().size)
        assertEquals(1, dispatchCalls)
    }

    @Test
    fun deliveryHint_withoutAbsent_doesNotInvokeAbsentEvaluateRetry() {
        policy.onDeliveryHint(record, "LINK_READY")
        assertTrue(evaluateLogs().isEmpty())
    }

    @Test
    fun mismatchedDeliveryAttemptId_doesNotEvaluate() {
        record.recoveryOfferDeliveryAttemptId = 2L
        RecoveryDeliveryFact.emitRemoteIngressAbsent(identity(deliveryAttemptId = 1L), sessionId)
        assertTrue(evaluateLogs().isEmpty())
    }
}
