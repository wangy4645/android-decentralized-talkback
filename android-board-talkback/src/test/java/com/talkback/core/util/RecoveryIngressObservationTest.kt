package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecoveryIngressObservationTest {

    private val factLines = mutableListOf<String>()
    private val observationLines = mutableListOf<String>()

    @Before
    fun setUp() {
        factLines.clear()
        observationLines.clear()
        RecoveryDeliveryFact.resetForTest { factLines.add(it) }
        RecoveryIngressObservation.resetForTest(
            deadlineMs = 5_000L,
            observationLog = { observationLines.add(it) }
        )
    }

    @After
    fun tearDown() {
        RecoveryIngressObservation.shutdownForTest()
        RecoveryDeliveryFact.resetForTest()
    }

    private fun identity(
        lineage: String = "L1",
        deliveryAttemptId: Long = 1L,
        from: String = "M02",
        to: String = "M03"
    ): RecoveryDeliveryFact.Identity = RecoveryDeliveryFact.Identity(
        offerLineageId = lineage,
        recoveryAttemptId = 7L,
        obligationGeneration = 5L,
        deliveryAttemptId = deliveryAttemptId,
        from = from,
        to = to
    )

    private fun observedCount(): Int =
        factLines.count { it.startsWith("RECOVERY_REMOTE_INGRESS_OBSERVED") }

    private fun absentCount(): Int =
        factLines.count { it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") }

    @Test
    fun localAcceptThenIngress_emitsObservedNoAbsent() {
        val id = identity()
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, "sess-1")
        RecoveryIngressObservation.onIngressEvidenceForTest(id, "sess-1")
        assertEquals(1, observedCount())
        assertEquals(0, absentCount())
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")
        assertEquals(0, absentCount())
    }

    @Test
    fun localAcceptThenDeadline_emitsSingleAbsent() {
        val id = identity()
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, "sess-1")
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")
        assertEquals(1, absentCount())
        assertEquals(0, observedCount())
        // INV-DELIVERY-OBS-001: deadline must preserve window identity (not synthetic 0,0).
        assertTrue(
            factLines.any {
                it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") &&
                    it.contains("recoveryAttemptId=7") &&
                    it.contains("obligationGeneration=5") &&
                    it.contains("deliveryAttemptId=1") &&
                    it.contains("reason=WINDOW_DEADLINE")
            }
        )
        assertFalse(
            factLines.any {
                it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") &&
                    it.contains("recoveryAttemptId=0") &&
                    it.contains("obligationGeneration=0")
            }
        )
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")
        assertEquals(1, absentCount())
    }

    @Test
    fun deadlineAbsent_preservesAttemptAndGenerationFromLocalAccepted() {
        val id = identity(
            lineage = "L1",
            deliveryAttemptId = 2L
        ).copy(recoveryAttemptId = 11L, obligationGeneration = 3L)
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, "sess-1")
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")
        assertEquals(1, absentCount())
        assertTrue(
            factLines.any {
                it.startsWith("RECOVERY_REMOTE_INGRESS_ABSENT") &&
                    it.contains("offerLineageId=L1") &&
                    it.contains("recoveryAttemptId=11") &&
                    it.contains("obligationGeneration=3") &&
                    it.contains("deliveryAttemptId=2")
            }
        )
    }

    @Test
    fun deadlineThenIngress_isObservationOnlyNoRevokeAbsent() {
        val id = identity()
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, "sess-1")
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")
        assertEquals(1, absentCount())
        RecoveryIngressObservation.onIngressEvidenceForTest(id, "sess-1")
        assertEquals(1, absentCount())
        assertEquals(0, observedCount())
        assertTrue(
            observationLines.any {
                it.contains("RECOVERY_REMOTE_INGRESS_LATE_OBSERVATION_ONLY") &&
                    it.contains("CLOSED_ABSENT")
            }
        )
    }

    @Test
    fun absentThenTimerOrReachability_doesNotRepeatAbsent() {
        val id = identity()
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, "sess-1")
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")
        RecoveryIngressObservation.onReachabilityHint()
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")
        assertEquals(1, absentCount())
    }

    @Test
    fun supersedeThenOldIngress_isObservationOnly() {
        val id = identity()
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, "sess-1")
        RecoveryIngressObservation.onLineageSuperseded("L1")
        assertTrue(
            observationLines.any {
                it.contains("RECOVERY_INGRESS_WINDOW_CLOSED") &&
                    it.contains("offerLineageId=L1") &&
                    it.contains("state=CLOSED_SUPERSEDED")
            }
        )
        RecoveryIngressObservation.onIngressEvidenceForTest(id, "sess-1")
        assertEquals(0, observedCount())
        assertEquals(0, absentCount())
        assertTrue(
            observationLines.any {
                it.contains("RECOVERY_REMOTE_INGRESS_LATE_OBSERVATION_ONLY") &&
                    it.contains("LINEAGE_SUPERSEDED")
            }
        )
        RecoveryIngressObservation.fireWindowDeadlineForTest(id, "sess-1")
        assertEquals(0, absentCount())
    }

    @Test
    fun exhaustedThenIngress_isDiscarded() {
        val id = identity()
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, id, "sess-1")
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.DELIVERY_EXHAUSTED, id, "sess-1")
        RecoveryIngressObservation.onIngressEvidenceForTest(id, "sess-1")
        assertEquals(0, observedCount())
        assertEquals(0, absentCount())
        assertTrue(observationLines.isEmpty())
    }

    @Test
    fun newDeliveryAttempt_opensNewWindow() {
        val idN1 = identity(deliveryAttemptId = 1L)
        val idN2 = identity(deliveryAttemptId = 2L)
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, idN1, "sess-1")
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.LOCAL_ACCEPTED, idN2, "sess-1")
        RecoveryIngressObservation.fireWindowDeadlineForTest(idN1, "sess-1")
        RecoveryIngressObservation.fireWindowDeadlineForTest(idN2, "sess-1")
        assertEquals(2, absentCount())
        assertTrue(factLines.any { it.contains("deliveryAttemptId=1") && it.contains("ABSENT") })
        assertTrue(factLines.any { it.contains("deliveryAttemptId=2") && it.contains("ABSENT") })
    }

    @Test
    fun producerDoesNotReferenceAdmission() {
        val source = RecoveryIngressObservation::class.java.name
        assertFalse(source.contains("Admission"))
        assertFalse(source.contains("PR3"))
    }
}
