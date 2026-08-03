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
import java.util.concurrent.TimeUnit

/** D1 Slice-2B: evaluateRetry → admission gate (no dispatch, no budget). */
class RecoveryDeliveryPolicyAdmissionTest {

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
        record = pendingRecord(deliveryAttemptId = 1L)
        policy = buildPolicy(admission = defaultRecoveryAdmissionProjection())
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

    private fun buildPolicy(
        admission: PeerSignalingReachabilityProjection = defaultRecoveryAdmissionProjection()
    ): RecoveryOfferDeliveryPolicy {
        return RecoveryOfferDeliveryPolicy(
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
            canDispatchRecoverySignal = { _, _ -> true },
            evaluateRecoveryAdmission = { _, _ -> admission }
        ).also { it.bindEdgesLookup { record } }
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

    private fun emitAbsent(id: RecoveryDeliveryFact.Identity = identity()) {
        RecoveryDeliveryFact.emitRemoteIngressAbsent(id, sessionId)
    }

    @Test
    fun absentWithAdmissionPass_emitsRetryAdmitted() {
        emitAbsent()
        assertEquals(1, factLines.count { it.startsWith("RECOVERY_DELIVERY_RETRY_ADMITTED") })
        assertTrue(factLines.any { it.contains("deliveryAttemptId=2") })
        assertFalse(factLines.any { it.contains("RETRY_DEFERRED") })
        // Slice-2C: ADMITTED is immediately followed by dispatch (still one evaluation).
        assertEquals(1, dispatchCalls)
    }

    @Test
    fun absentWithAdmissionBlock_emitsRetryDeferred() {
        policy = buildPolicy(
            admission = PeerSignalingReachabilityProjection(
                confidence = PeerSignalingReachabilityConfidence.LOW,
                decision = AdmissionDecisionProjection.WAITING_LOW,
                reason = AdmissionConfidenceReason.NO_CURRENT_EPOCH_INBOUND,
                lastInboundAgeMs = null
            )
        )
        RecoveryDeliveryFact.bindIngressAbsentHandler { identity, sid ->
            policy.onRemoteIngressAbsent(record, identity, sid)
        }
        emitAbsent()
        val deferred = factLines.filter { it.startsWith("RECOVERY_DELIVERY_RETRY_DEFERRED") }
        assertEquals(1, deferred.size)
        assertTrue(deferred.single().contains("reason=ADMISSION_NOT_READY"))
        assertTrue(deferred.single().contains("deliveryAttemptId=1"))
        assertFalse(factLines.any { it.startsWith("RECOVERY_DELIVERY_RETRY_ADMITTED") })
        assertFalse(factLines.any { it.contains("DELIVERY_EXHAUSTED") })
        assertEquals(0, dispatchCalls)
    }

    @Test
    fun admissionBlock_preservesDeliveryAttemptAndLineage() {
        policy = buildPolicy(
            admission = PeerSignalingReachabilityProjection(
                confidence = PeerSignalingReachabilityConfidence.LOW,
                decision = AdmissionDecisionProjection.WAITING_STALE,
                reason = AdmissionConfidenceReason.INBOUND_STALE_FOR_RECOVERY_DISPATCH,
                lastInboundAgeMs = 8_000L
            )
        )
        RecoveryDeliveryFact.bindIngressAbsentHandler { identity, sid ->
            policy.onRemoteIngressAbsent(record, identity, sid)
        }
        emitAbsent()
        assertEquals(1L, record.recoveryOfferDeliveryAttemptId)
        assertEquals(lineageId, record.recoveryOfferLineageId)
        assertTrue(record.recoveryOfferDeliveryPhase.isAwaitingAck())
    }

    @Test
    fun reachabilityHintOnly_doesNotEvaluateRetry() {
        policy.onDeliveryHint(record, "PEER_REACHABILITY_RESTORED")
        assertTrue(policyLogs.any { it.contains("RECOVERY_RETRY_HINT_OBSERVED") })
        assertFalse(policyLogs.any { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") })
        assertTrue(factLines.none { it.contains("RETRY_ADMITTED") })
        assertEquals(0, dispatchCalls)
    }

    @Test
    fun timerOnly_doesNotEvaluateRetry() {
        policy.onOutboundDeliveryPending(record, identity(), sessionId)
        factLines.clear()
        policyLogs.clear()
        scheduler.schedule({}, 250, TimeUnit.MILLISECONDS).get()
        assertTrue(policyLogs.any { it.contains("RECOVERY_RETRY_TIMER_OBSERVED") })
        assertFalse(policyLogs.any { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") })
        assertTrue(factLines.none { it.contains("RETRY_ADMITTED") })
        assertEquals(0, dispatchCalls)
    }

    @Test
    fun duplicateAbsent_singleAdmissionEvaluation() {
        val id = identity()
        emitAbsent(id)
        emitAbsent(id)
        assertEquals(1, factLines.count { it.startsWith("RECOVERY_DELIVERY_RETRY_ADMITTED") })
        assertEquals(1, policyLogs.count { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") })
    }
}
