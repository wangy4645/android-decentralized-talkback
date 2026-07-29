package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            offerLineageId = "L9",
            recoveryAttemptId = 4L,
            obligationGeneration = 2L,
            deliveryAttemptId = 1L
        )
        val decoded = RecoveryReattachAckPayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }
}