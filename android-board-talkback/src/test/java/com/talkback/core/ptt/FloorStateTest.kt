package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloorStateTest {
    private val m01e01 = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
    private val m02e01 = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
    private val arbitrator = FloorArbitrator()

    @Test
    fun grantWhenNoOwner() {
        val floor = FloorState()
        val result = floor.tryGrant(m01e01, 1, 0, EndpointPriority.NORMAL, 100, arbitrator)
        assertEquals(FloorGrantResult.GRANTED, result)
        assertEquals(m01e01, floor.owner())
    }

    @Test
    fun denyStaleEpoch() {
        val floor = FloorState()
        floor.tryGrant(m01e01, 1, 0, EndpointPriority.NORMAL, 100, arbitrator)
        floor.release(m01e01)
        val result = floor.tryGrant(m02e01, 2, 0, EndpointPriority.NORMAL, 200, arbitrator)
        assertEquals(FloorGrantResult.STALE_VERSION, result)
    }

    @Test
    fun emergencyPreemptsNormalHolder() {
        val floor = FloorState()
        floor.tryGrant(m01e01, 1, 0, EndpointPriority.NORMAL, 100, arbitrator)
        val result = floor.tryGrant(m02e01, 2, 0, EndpointPriority.EMERGENCY, 200, arbitrator)
        assertEquals(FloorGrantResult.PREEMPTED, result)
        assertEquals(m02e01, floor.owner())
    }

    @Test
    fun dispatchDoesNotPreemptNormalHolder() {
        val floor = FloorState()
        floor.tryGrant(m01e01, 1, 0, EndpointPriority.NORMAL, 100, arbitrator)
        val result = floor.tryGrant(m02e01, 2, 0, EndpointPriority.DISPATCH, 200, arbitrator)
        assertEquals(FloorGrantResult.DENIED, result)
        assertEquals(m01e01, floor.owner())
    }

    @Test
    fun releaseClearsOwner() {
        val floor = FloorState()
        floor.tryGrant(m01e01, 1, 0, EndpointPriority.NORMAL, 100, arbitrator)
        floor.release(m01e01)
        assertNull(floor.owner())
    }

    @Test
    fun holderEmergencyPriorityBeatsNormalChallenger() {
        val floor = FloorState()
        floor.tryGrant(m02e01, 1, 0, EndpointPriority.EMERGENCY, 100, arbitrator)
        val result = floor.tryGrant(m01e01, 2, 0, EndpointPriority.NORMAL, 200, arbitrator)
        assertEquals(FloorGrantResult.DENIED, result)
        assertEquals(m02e01, floor.owner())
    }

    @Test
    fun applyGrantSyncsOwnerAndVersion() {
        val floor = FloorState()
        floor.applyGrant(m02e01, 5, 2, EndpointPriority.EMERGENCY)
        assertEquals(m02e01, floor.owner())
        assertEquals(5, floor.version())
        assertEquals(2, floor.epoch())
    }

    @Test
    fun applyGrantIgnoresStaleEpoch() {
        val floor = FloorState()
        floor.tryGrant(m01e01, 1, 0, EndpointPriority.NORMAL, 100, arbitrator)
        floor.release(m01e01)
        floor.applyGrant(m02e01, 2, 0, EndpointPriority.NORMAL)
        assertNull(floor.owner())
    }

    @Test
    fun applySnapshot_convergesEpochFromAuthority() {
        val floor = FloorState()
        val result = floor.applySnapshot(m01e01, 5, 5, EndpointPriority.NORMAL)
        assertEquals(SnapshotResult.OWNER_CHANGED, result)
        assertEquals(5, floor.epoch())
        assertEquals(5, floor.version())
        assertEquals(m01e01, floor.owner())
    }

    @Test
    fun applySnapshot_assignsVersionDirectly_notMax() {
        val floor = FloorState()
        floor.nextRequestVersion()
        repeat(99) { floor.nextRequestVersion() }
        assertEquals(100, floor.version())
        val result = floor.applySnapshot(m02e01, 6, 5, EndpointPriority.NORMAL)
        assertEquals(SnapshotResult.OWNER_CHANGED, result)
        assertEquals(6, floor.version())
        assertEquals(5, floor.epoch())
    }

    @Test
    fun applySnapshot_sameEpochOlderVersion_unchanged() {
        val floor = FloorState()
        floor.applySnapshot(m01e01, 5, 2, EndpointPriority.NORMAL)
        val result = floor.applySnapshot(m02e01, 3, 2, EndpointPriority.NORMAL)
        assertEquals(SnapshotResult.UNCHANGED, result)
        assertEquals(m01e01, floor.owner())
        assertEquals(5, floor.version())
    }

    @Test
    fun applySnapshot_nullOwnerClearsStaleOwner() {
        val floor = FloorState()
        floor.applySnapshot(m01e01, 3, 2, EndpointPriority.NORMAL)
        val result = floor.applySnapshot(null, 8, 7, EndpointPriority.NORMAL)
        assertEquals(SnapshotResult.OWNER_CHANGED, result)
        assertNull(floor.owner())
        assertEquals(7, floor.epoch())
        assertEquals(8, floor.version())
    }

    @Test
    fun applySnapshot_ignoredOldEpoch() {
        val floor = FloorState()
        floor.applySnapshot(m01e01, 5, 5, EndpointPriority.NORMAL)
        val result = floor.applySnapshot(m02e01, 10, 3, EndpointPriority.NORMAL)
        assertEquals(SnapshotResult.IGNORED_OLD_EPOCH, result)
        assertEquals(m01e01, floor.owner())
        assertEquals(5, floor.epoch())
    }

    @Test
    fun applySnapshot_sameEpochNewerVersion_updatesWithoutOwnerChange() {
        val floor = FloorState()
        floor.applySnapshot(m01e01, 3, 2, EndpointPriority.NORMAL)
        val result = floor.applySnapshot(m01e01, 5, 2, EndpointPriority.NORMAL)
        assertEquals(SnapshotResult.UPDATED, result)
        assertEquals(m01e01, floor.owner())
        assertEquals(5, floor.version())
    }
}
