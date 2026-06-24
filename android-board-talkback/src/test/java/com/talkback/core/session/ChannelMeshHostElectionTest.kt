package com.talkback.core.session

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMeshHostElectionTest {

    @Test
    fun electHost_minModuleId() {
        assertEquals(
            ModuleId("M01"),
            ChannelMeshHostElection.electHost(ModuleId("M03"), setOf("M01", "M02"))
        )
    }

    @Test
    fun isLocalHost_onlyMin() {
        assertTrue(ChannelMeshHostElection.isLocalHost(ModuleId("M01"), setOf("M02", "M03")))
        assertFalse(ChannelMeshHostElection.isLocalHost(ModuleId("M02"), setOf("M01", "M03")))
    }

    @Test
    fun electHost_matchesCoordinatorBootstrapWithEmptyHealth() {
        val members = setOf("M01", "M02", "M03")
        val health = mapOf(
            "M01" to AnchorHealthSnapshot(charging = false, batteryPercent = 30),
            "M02" to AnchorHealthSnapshot(charging = true, batteryPercent = 100),
            "M03" to AnchorHealthSnapshot(charging = true, batteryPercent = 95)
        )
        val bootstrapHost = ChannelMeshHostElection.electHost(ModuleId("M02"), members, emptyMap())
        val healthElect = AnchorRanking.electForBootstrap(
            members = members.map { ModuleId(it) },
            localModuleId = ModuleId("M02"),
            healthByModule = health
        )!!.primary
        assertEquals(ModuleId("M01"), bootstrapHost)
        assertEquals(ModuleId("M02"), healthElect)
    }

    @Test
    fun nextHost_deterministic() {
        assertEquals(
            ModuleId("M02"),
            ChannelMeshHostElection.nextHost(listOf("M01", "M02", "M03"), "M01")
        )
    }
}
