package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RecoveryReattachAckPayloadTest {

    @Test
    fun roundTrip() {
        val original = RecoveryReattachAckPayload(
            offerLineageId = "L1",
            recoveryAttemptId = 2L,
            obligationGeneration = 3L,
            deliveryAttemptId = 1L,
            handlerOutcome = RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        val decoded = RecoveryReattachAckPayload.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun decodeWithoutHandlerOutcome_returnsNull() {
        val raw = """{"offerLineageId":"L1","recoveryAttemptId":2,"obligationGeneration":3,"deliveryAttemptId":1}"""
        assertNull(RecoveryReattachAckPayload.decode(raw))
    }

    @Test
    fun decodeAlreadySatisfied() {
        val raw = """{"offerLineageId":"L2","recoveryAttemptId":2,"obligationGeneration":1,"deliveryAttemptId":3,"handlerOutcome":"ALREADY_SATISFIED"}"""
        val decoded = RecoveryReattachAckPayload.decode(raw)
        assertNotNull(decoded)
        assertEquals(RecoveryHandlerOutcome.ALREADY_SATISFIED, decoded!!.handlerOutcome)
    }
}