package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import com.talkback.core.model.RemoteEndpointInfo
import com.talkback.core.sync.StateSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPriorityPolicyTest {
    @Test
    fun effectivePriority_prefersRegisteredOverClaimed() {
        val effective = FloorPriorityPolicy.effectivePriority(
            registered = EndpointPriority.NORMAL,
            claimed = EndpointPriority.EMERGENCY
        )
        assertEquals(EndpointPriority.NORMAL, effective)
    }

    @Test
    fun effectivePriority_fallsBackToClaimedWhenDirectoryMissing() {
        val effective = FloorPriorityPolicy.effectivePriority(
            registered = null,
            claimed = EndpointPriority.DISPATCH
        )
        assertEquals(EndpointPriority.DISPATCH, effective)
    }

    @Test
    fun spoofedEmergencyClaim_cannotPreemptWhenDirectorySaysNormal() {
        val sync = StateSyncManager()
        val requester = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
        sync.updateEndpoints(
            "M02",
            listOf(
                RemoteEndpointInfo(
                    endpointId = "E01",
                    displayName = "Ops",
                    online = true,
                    priority = EndpointPriority.NORMAL
                )
            )
        )
        val effective = FloorPriorityPolicy.effectivePriority(
            registered = sync.registeredPriority(requester),
            claimed = EndpointPriority.EMERGENCY
        )
        assertEquals(EndpointPriority.NORMAL, effective)

        val holder = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val floor = FloorState()
        val arbitrator = FloorArbitrator()
        floor.tryGrant(holder, 1, 0, EndpointPriority.NORMAL, 100, arbitrator)
        val result = floor.tryGrant(requester, 2, 0, effective, 200, arbitrator)
        assertEquals(FloorGrantResult.DENIED, result)
        assertEquals(holder, floor.owner())
    }

    @Test
    fun canPreempt_onlyEmergencyOverLowerHolder() {
        assertTrue(
            FloorPriorityPolicy.canPreempt(
                EndpointPriority.EMERGENCY,
                EndpointPriority.NORMAL
            )
        )
        assertFalse(
            FloorPriorityPolicy.canPreempt(
                EndpointPriority.DISPATCH,
                EndpointPriority.NORMAL
            )
        )
        assertFalse(
            FloorPriorityPolicy.canPreempt(
                EndpointPriority.EMERGENCY,
                EndpointPriority.EMERGENCY
            )
        )
    }
}
