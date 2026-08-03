package com.talkback.core.util

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.session.ConferenceEdgeKey
import com.talkback.core.session.EdgeRecoveryPhase
import com.talkback.core.session.EdgeRecoveryRecord
import com.talkback.core.session.RecoveryOfferDeliveryPhase
import com.talkback.core.session.RecoveryOfferDeliveryPolicy
import com.talkback.core.session.defaultRecoveryAdmissionProjection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/** D1 Option A: ingress-miss injection + ABSENT ownership chain (deterministic). */
class D1IngressMissInjectionTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val factLines = mutableListOf<String>()
    private val policyLogs = mutableListOf<String>()
    private val dispatchCalls = mutableListOf<Long>()

    @Before
    fun setUp() {
        factLines.clear()
        policyLogs.clear()
        dispatchCalls.clear()
        D1IngressMissDebugInjection.resetForTest { /* discard */ }
        RecoveryDeliveryFact.resetForTest { factLines.add(it) }
        RecoveryIngressObservation.resetForTest(deadlineMs = 5_000L)
    }

    @After
    fun tearDown() {
        D1IngressMissDebugInjection.resetForTest()
        RecoveryIngressObservation.shutdownForTest()
        RecoveryDeliveryFact.resetForTest()
        scheduler.shutdownNow()
    }

    private fun envelope(
        joinIntent: String = "RECOVERY_REATTACH",
        lineage: String = "L1",
        deliveryAttemptId: Long = 1L
    ): SignalEnvelope {
        val payload =
            """{"sdp":"v=0","channelId":"ch-1","members":["M02","M03"],"initiatorModuleId":"M02","floorAuthorityModuleId":"M02","joinIntent":"$joinIntent","offerLineageId":"$lineage","deliveryAttemptId":$deliveryAttemptId}"""
        return SignalEnvelope(
            type = SignalType.GROUP_JOIN,
            from = EndpointAddress(ModuleId("M02"), EndpointId("E01")),
            to = EndpointAddress(ModuleId("M03"), EndpointId("E01")),
            sessionId = "sess-1",
            timestampMs = 1L,
            payload = payload,
            nonce = "n1",
            signature = ""
        )
    }

    @Test
    fun armed_dropsRecoveryReattachOnly() {
        D1IngressMissDebugInjection.armDropRecoveryOfferIngress()
        assertTrue(D1IngressMissDebugInjection.consumeDropIfArmed(envelope()))
        assertEquals(1, D1IngressMissDebugInjection.dropCount())
        assertFalse(
            D1IngressMissDebugInjection.consumeDropIfArmed(
                envelope(joinIntent = "NORMAL_JOIN")
            )
        )
        assertEquals(1, D1IngressMissDebugInjection.dropCount())
    }

    @Test
    fun disarmed_doesNotDrop() {
        assertFalse(D1IngressMissDebugInjection.consumeDropIfArmed(envelope()))
        assertEquals(0, D1IngressMissDebugInjection.dropCount())
    }

    @Test
    fun armAndClear_aloneEmitNoRecoveryDeliveryFacts() {
        D1IngressMissDebugInjection.armDropRecoveryOfferIngress()
        assertTrue(D1IngressMissDebugInjection.isArmed())
        D1IngressMissDebugInjection.clear()
        assertFalse(D1IngressMissDebugInjection.isArmed())
        assertTrue(
            "debug arm/clear must not emit production recovery facts",
            factLines.isEmpty()
        )
    }

    @Test
    fun dropPreventsObserved_andSenderAbsentDrivesRetryChain() {
        D1IngressMissDebugInjection.armDropRecoveryOfferIngress()
        assertTrue(D1IngressMissDebugInjection.consumeDropIfArmed(envelope()))
        assertFalse(factLines.any { it.startsWith("RECOVERY_REMOTE_INGRESS_OBSERVED") })

        val id = RecoveryDeliveryFact.Identity(
            offerLineageId = "L1",
            recoveryAttemptId = 1L,
            obligationGeneration = 1L,
            deliveryAttemptId = 1L,
            from = "M02",
            to = "M03"
        )
        val record = EdgeRecoveryRecord(
            key = ConferenceEdgeKey("sess-1", "M03"),
            phase = EdgeRecoveryPhase.REATTACH_ACCEPTED,
            channelId = "CH-1",
            recoveryAttemptId = 1L,
            recoveryStartedAtMs = 0L,
            recoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.PENDING,
            recoveryOfferLineageId = "L1",
            recoveryOfferDeliveryAttemptId = 1L,
            recoveryOfferLastDispatchAtMs = 0L
        )
        val policy = RecoveryOfferDeliveryPolicy(
            localModuleId = "M02",
            maxDeliveryAttempts = 3,
            deliveryRetryIntervalMs = 5_000L,
            deliveryRetryMinGapMs = 0L,
            clock = { 0L },
            scheduler = scheduler,
            onLog = { policyLogs.add(it) },
            onDispatchRecoveryOffer = { _, _, _, attempt ->
                dispatchCalls.add(attempt)
                true
            },
            canDispatchRecoverySignal = { _, _ -> true },
            evaluateRecoveryAdmission = { _, _ -> defaultRecoveryAdmissionProjection() }
        ).also { it.bindEdgesLookup { record } }
        RecoveryDeliveryFact.bindIngressAbsentHandler { identity, sid ->
            policy.onRemoteIngressAbsent(record, identity, sid)
        }

        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, "sess-1")
        assertFalse(factLines.any { it.startsWith("RECOVERY_REMOTE_INGRESS_OBSERVED") })
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")

        assertTrue(factLines.any { it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") })
        assertTrue(policyLogs.any { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") && it.contains("REMOTE_INGRESS_ABSENT") })
        assertTrue(factLines.any { it.startsWith("RECOVERY_DELIVERY_RETRY_ADMITTED") })
        assertEquals(listOf(2L), dispatchCalls)
        assertEquals(2L, record.recoveryOfferDeliveryAttemptId)
        assertFalse(policyLogs.any { it.contains("RECOVERY_DELIVERY_RETRY_EVALUATE") && !it.contains("REMOTE_INGRESS_ABSENT") })
    }
}