package com.talkback.appprod.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointTextSendErrorMapperTest {

    @Test
    fun success_mapsToNull() {
        assertNull(EndpointTextSendErrorMapper.map(Result.success(Unit)))
    }

    @Test
    fun serviceStopped_mapsExplicitly() {
        assertEquals(
            "SERVICE_STOPPED",
            EndpointTextSendErrorMapper.map(
                Result.failure(IllegalStateException("SERVICE_STOPPED"))
            )
        )
    }

    @Test
    fun unreachable_mapsFromReasonConstant() {
        assertEquals(
            "UNREACHABLE",
            EndpointTextSendErrorMapper.map(
                Result.failure(IllegalStateException("UNREACHABLE"))
            )
        )
    }

    @Test
    fun textTooLong_mapsFromReasonConstant() {
        assertEquals(
            "TEXT_TOO_LONG",
            EndpointTextSendErrorMapper.map(
                Result.failure(IllegalStateException("TEXT_TOO_LONG"))
            )
        )
    }

    @Test
    fun sendFailed_mapsFromReasonConstant() {
        assertEquals(
            "SEND_FAILED",
            EndpointTextSendErrorMapper.map(
                Result.failure(IllegalStateException("SEND_FAILED"))
            )
        )
    }

    @Test
    fun unknownFailure_mapsToGeneric() {
        assertEquals(
            "FAILED",
            EndpointTextSendErrorMapper.map(
                Result.failure(IllegalStateException("something else"))
            )
        )
    }
}
