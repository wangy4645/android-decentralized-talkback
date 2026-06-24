package com.talkback.core.session

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorRankingTest {

    @Test
    fun elect_prefersChargingOverBattery() {
        val members = listOf(ModuleId("M01"), ModuleId("M02"))
        val now = 1_000_000L
        val health = mapOf(
            "M01" to AnchorHealthSnapshot(charging = false, batteryPercent = 100, onlineSinceMs = now - 3_600_000L),
            "M02" to AnchorHealthSnapshot(charging = true, batteryPercent = 50, onlineSinceMs = now - 3_600_000L)
        )
        val roles = AnchorRanking.elect(members, health, now)!!
        assertEquals(ModuleId("M02"), roles.primary)
        assertEquals(ModuleId("M01"), roles.backup)
    }

    @Test
    fun resolveSplitBrain_higherEpochWins() {
        val winner = AnchorRanking.resolveSplitBrain(
            leftModuleId = "M01",
            leftEpoch = 100L,
            rightModuleId = "M02",
            rightEpoch = 101L,
            healthByModule = emptyMap()
        )
        assertEquals("M02", winner)
    }

    @Test
    fun resolveSplitBrain_sameEpochUsesRanking() {
        val now = 2_000_000L
        val health = mapOf(
            "M01" to AnchorHealthSnapshot(charging = false, batteryPercent = 50, onlineSinceMs = now - 60_000L),
            "M02" to AnchorHealthSnapshot(charging = true, batteryPercent = 80, onlineSinceMs = now - 60_000L)
        )
        val winner = AnchorRanking.resolveSplitBrain(
            leftModuleId = "M01",
            leftEpoch = 100L,
            rightModuleId = "M02",
            rightEpoch = 100L,
            healthByModule = health,
            nowMs = now
        )
        assertEquals("M02", winner)
    }

    @Test
    fun initialEpoch_is100() {
        assertEquals(100L, AnchorRanking.INITIAL_ANCHOR_EPOCH)
        assertEquals(101L, AnchorAuthority.nextEpochAfterFailover(100L))
    }

    @Test
    fun electForBootstrap_fallsBackToMinUntilHelloDirectoryComplete() {
        val members = listOf(ModuleId("M03"), ModuleId("M01"), ModuleId("M02"))
        val roles = AnchorRanking.electForBootstrap(
            members = members,
            localModuleId = ModuleId("M02"),
            healthByModule = mapOf(
                "M02" to AnchorHealthSnapshot(charging = true, batteryPercent = 100)
            )
        )!!
        assertEquals(ModuleId("M01"), roles.primary)
        assertEquals(ModuleId("M02"), roles.backup)
    }
}
