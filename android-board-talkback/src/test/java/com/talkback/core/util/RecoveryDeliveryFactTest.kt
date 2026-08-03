package com.talkback.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryDeliveryFactTest {

    @Test
    fun emitRequested_includesLineageAndAttempt() {
        val lines = mutableListOf<String>()
        RecoveryDeliveryFact.resetForTest { lines.add(it) }
        val identity = RecoveryDeliveryFact.Identity(
            offerLineageId = "L1",
            recoveryAttemptId = 7L,
            obligationGeneration = 5L,
            deliveryAttemptId = 1L,
            from = "M02",
            to = "M03"
        )
        RecoveryDeliveryFact.emit(RecoveryDeliveryFact.Phase.REQUESTED, identity, "sess-1")
        val line = lines.single()
        assertTrue(line.startsWith("RECOVERY_DELIVERY_REQUESTED"))
        assertTrue(line.contains("offerLineageId=L1"))
        assertTrue(line.contains("recoveryAttemptId=7"))
        assertTrue(line.contains("obligationGeneration=5"))
        assertTrue(line.contains("deliveryAttemptId=1"))
        assertTrue(line.contains("from=M02"))
        assertTrue(line.contains("to=M03"))
        assertTrue(line.contains("session=sess-1"))
        RecoveryDeliveryFact.resetForTest()
    }

    @Test
    fun matchesAck_requiresAllFields() {
        val identity = RecoveryDeliveryFact.Identity(
            offerLineageId = "L1",
            recoveryAttemptId = 2L,
            obligationGeneration = 3L,
            deliveryAttemptId = 1L,
            from = "M02",
            to = "M03"
        )
        val ack = RecoveryReattachAckFields(
            offerLineageId = "L1",
            recoveryAttemptId = 2L,
            obligationGeneration = 3L,
            deliveryAttemptId = 1L,
            handlerOutcome = com.talkback.core.model.RecoveryHandlerOutcome.ACCEPTED
        )
        assertTrue(RecoveryDeliveryFact.matchesAck(identity, ack))
        assertFalse(
            RecoveryDeliveryFact.matchesAck(
                identity,
                ack.copy(deliveryAttemptId = 2L)
            )
        )
        assertTrue(
            RecoveryDeliveryFact.matchesAck(
                identity.copy(recoveryAttemptId = 99L),
                ack.copy(recoveryAttemptId = 1L)
            )
        )
    }
}