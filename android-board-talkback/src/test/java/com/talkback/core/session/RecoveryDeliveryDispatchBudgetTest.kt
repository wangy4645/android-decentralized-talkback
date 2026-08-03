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

/** D1 Slice-2C: dispatch + budget consume + EXHAUSTED fence. */
class RecoveryDeliveryDispatchBudgetTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val policyLogs = mutableListOf<String>()
    private val factLines = mutableListOf<String>()
    private val dispatchCalls = mutableListOf<Long>()
    private var dispatchResult = true
    private lateinit var policy: RecoveryOfferDeliveryPolicy
    private lateinit var record: EdgeRecoveryRecord

    private val sessionId = "sess-1"
    private val remoteModuleId = "M03"
    private val lineageId = "L1"

    @Before
    fun setUp() {
        policyLogs.clear()
        factLines.clear()
        dispatchCalls.clear()
        dispatchResult = true
        RecoveryDeliveryFact.resetForTest { factLines.add(it) }
        RecoveryIngressObservation.resetForTest(deadlineMs = 5_000L)
        record = pendingRecord(deliveryAttemptId = 1L)
        policy = buildPolicy()
        bindAbsent()
    }

    @After
    fun tearDown() {
        RecoveryIngressObservation.shutdownForTest()
        RecoveryDeliveryFact.resetForTest()
        scheduler.shutdownNow()
    }

    private fun bindAbsent() {
        RecoveryDeliveryFact.bindIngressAbsentHandler { identity, sid ->
            policy.onRemoteIngressAbsent(record, identity, sid)
        }
    }

    private fun buildPolicy(
        admission: PeerSignalingReachabilityProjection = defaultRecoveryAdmissionProjection(),
        maxAttempts: Int = 3,
        canDispatch: Boolean = true
    ): RecoveryOfferDeliveryPolicy {
        return RecoveryOfferDeliveryPolicy(
            localModuleId = "M02",
            maxDeliveryAttempts = maxAttempts,
            deliveryRetryIntervalMs = 5_000L,
            deliveryRetryMinGapMs = 0L,
            clock = { 0L },
            scheduler = scheduler,
            onLog = { policyLogs.add(it) },
            onDispatchRecoveryOffer = { _, _, _, attempt ->
                dispatchCalls.add(attempt)
                dispatchResult
            },
            canDispatchRecoverySignal = { _, _ -> canDispatch },
            evaluateRecoveryAdmission = { _, _ -> admission }
        ).also { it.bindEdgesLookup { record } }
    }

    private fun pendingRecord(
        deliveryAttemptId: Long,
        lineage: String = lineageId,
        phase: RecoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.PENDING
    ): EdgeRecoveryRecord {
        return EdgeRecoveryRecord(
            key = ConferenceEdgeKey(sessionId, remoteModuleId),
            phase = EdgeRecoveryPhase.REATTACH_ACCEPTED,
            channelId = "CH-1",
            recoveryAttemptId = 1L,
            recoveryStartedAtMs = 0L,
            recoveryOfferDeliveryPhase = phase,
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

    private fun emitAbsent(deliveryAttemptId: Long = record.recoveryOfferDeliveryAttemptId) {
        RecoveryDeliveryFact.emitRemoteIngressAbsent(identity(deliveryAttemptId), sessionId)
    }

    @Test
    fun admissionPass_dispatchesOnce() {
        emitAbsent(1L)
        assertEquals(listOf(2L), dispatchCalls)
        assertTrue(factLines.any { it.startsWith("RECOVERY_DELIVERY_RETRY_ADMITTED") })
    }

    @Test
    fun dispatchSuccess_incrementsAttempt() {
        emitAbsent(1L)
        assertEquals(2L, record.recoveryOfferDeliveryAttemptId)
        assertEquals(RecoveryOfferDeliveryPhase.RETRY_PENDING, record.recoveryOfferDeliveryPhase)
        assertTrue(factLines.any { it.contains("DELIVERY_RETRY_PENDING") && it.contains("deliveryAttemptId=2") })
    }

    @Test
    fun admissionBlock_attemptUnchanged() {
        policy = buildPolicy(
            admission = PeerSignalingReachabilityProjection(
                confidence = PeerSignalingReachabilityConfidence.LOW,
                decision = AdmissionDecisionProjection.WAITING_LOW,
                reason = AdmissionConfidenceReason.NO_CURRENT_EPOCH_INBOUND,
                lastInboundAgeMs = null
            )
        )
        bindAbsent()
        emitAbsent(1L)
        assertTrue(dispatchCalls.isEmpty())
        assertEquals(1L, record.recoveryOfferDeliveryAttemptId)
        assertTrue(record.recoveryOfferDeliveryPhase.isAwaitingAck())
    }

    @Test
    fun dispatchFailure_attemptUnchanged() {
        dispatchResult = false
        emitAbsent(1L)
        assertEquals(listOf(2L), dispatchCalls)
        assertEquals(1L, record.recoveryOfferDeliveryAttemptId)
        assertTrue(factLines.any { it.contains("RETRY_DEFERRED") && it.contains("dispatch_failed") })
        assertFalse(factLines.any { it.contains("DELIVERY_EXHAUSTED") })
    }

    @Test
    fun duplicateAbsent_singleDispatch() {
        emitAbsent(1L)
        emitAbsent(1L)
        assertEquals(1, dispatchCalls.size)
        assertEquals(2L, record.recoveryOfferDeliveryAttemptId)
    }

    @Test
    fun thirdAttemptAbsent_exhaustsLineage() {
        // N=1 dispatched; ABSENT→N=2; ABSENT→N=3; ABSENT→EXHAUSTED
        emitAbsent(1L)
        assertEquals(2L, record.recoveryOfferDeliveryAttemptId)
        emitAbsent(2L)
        assertEquals(3L, record.recoveryOfferDeliveryAttemptId)
        emitAbsent(3L)
        assertEquals(RecoveryOfferDeliveryPhase.EXHAUSTED, record.recoveryOfferDeliveryPhase)
        assertEquals(3L, record.recoveryOfferDeliveryAttemptId)
        assertTrue(factLines.any { it.contains("DELIVERY_EXHAUSTED") })
        assertEquals(listOf(2L, 3L), dispatchCalls)
    }

    @Test
    fun exhaustedPlusLateIngress_discarded() {
        record = pendingRecord(deliveryAttemptId = 3L)
        policy = buildPolicy()
        bindAbsent()
        emitAbsent(3L)
        assertEquals(RecoveryOfferDeliveryPhase.EXHAUSTED, record.recoveryOfferDeliveryPhase)

        val before = factLines.size
        RecoveryIngressObservation.onIngressEvidenceForTest(identity(3L), sessionId)
        assertFalse(factLines.drop(before).any { it.startsWith("RECOVERY_REMOTE_INGRESS_OBSERVED") })
    }

    @Test
    fun exhaustedPlusAbsent_noRetry() {
        record = pendingRecord(
            deliveryAttemptId = 3L,
            phase = RecoveryOfferDeliveryPhase.EXHAUSTED
        )
        policy = buildPolicy()
        bindAbsent()
        dispatchCalls.clear()
        factLines.clear()
        emitAbsent(3L)
        assertTrue(dispatchCalls.isEmpty())
        assertFalse(policyLogs.any { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") })
        assertFalse(factLines.any { it.contains("RETRY_ADMITTED") })
    }

    @Test
    fun supersede_newLineageFreshBudget() {
        record = pendingRecord(
            deliveryAttemptId = 3L,
            phase = RecoveryOfferDeliveryPhase.EXHAUSTED
        )
        policy = buildPolicy()
        bindAbsent()
        RecoveryIngressObservation.onLineageSuperseded("L1")

        val newId = identity(deliveryAttemptId = 1L, lineage = "L2")
        policy.onLineageSuperseded(record, newId, sessionId)
        assertEquals("L2", record.recoveryOfferLineageId)
        assertEquals(1L, record.recoveryOfferDeliveryAttemptId)
        assertTrue(record.recoveryOfferDeliveryPhase.isAwaitingAck())

        RecoveryDeliveryFact.emitRemoteIngressAbsent(identity(1L, "L2"), sessionId)
        assertEquals(listOf(2L), dispatchCalls)
        assertEquals(2L, record.recoveryOfferDeliveryAttemptId)
        assertEquals("L2", record.recoveryOfferLineageId)
    }

    @Test
    fun n1RetryOpensIndependentN2Window() {
        emitAbsent(1L)
        assertEquals(2L, record.recoveryOfferDeliveryAttemptId)

        RecoveryDeliveryFact.emit(
            RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED,
            identity(2L),
            sessionId
        )
        RecoveryIngressObservation.fireWindowDeadlineForTest(identity(2L), sessionId)
        assertTrue(
            factLines.any {
                it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") && it.contains("deliveryAttemptId=2")
            }
        )
        assertEquals(listOf(2L, 3L), dispatchCalls)
        assertEquals(3L, record.recoveryOfferDeliveryAttemptId)
    }
}
