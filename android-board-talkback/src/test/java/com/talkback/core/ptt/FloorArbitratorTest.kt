package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Test

class FloorArbitratorTest {
    @Test
    fun emergencyPriorityWins() {
        val arbitrator = FloorArbitrator()
        val low = FloorClaim(
            endpoint = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            priority = EndpointPriority.NORMAL,
            requestTsMs = 100
        )
        val high = FloorClaim(
            endpoint = EndpointAddress(ModuleId("M02"), EndpointId("E01")),
            priority = EndpointPriority.EMERGENCY,
            requestTsMs = 200
        )

        val winner = arbitrator.pickWinner(listOf(low, high))
        assertEquals("M02-E01", winner?.endpoint?.key)
    }
}
