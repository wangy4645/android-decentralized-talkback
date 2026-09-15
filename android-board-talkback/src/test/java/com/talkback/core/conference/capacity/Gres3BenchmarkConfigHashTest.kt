package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Gres3BenchmarkConfigHashTest {
    private fun sampleConfig(executionModel: Gres3SenderExecutionModel): Gres3CapacityHarnessConfig =
        Gres3CapacityHarnessConfig(
            runId = "hash-test",
            runClass = Gres3RunClass.QUALIFICATION,
            deviceLabel = "M03",
            appBuildSha = "test",
            warmupSec = 60,
            measurementSec = 90,
            cooldownSec = 30,
            endpoints = Gres3CapacityTopology.distinctLocalPorts(baseIp = "127.0.0.1", basePort = 47_001),
            executionModel = executionModel,
            harnessPhase = Gres3HarnessPhase.H1d,
        )

    @Test
    fun compute_differsBetweenE0AndE1() {
        val e0 = Gres3BenchmarkConfigHash.compute(sampleConfig(Gres3SenderExecutionModel.SHARED_SOCKET_SEQUENTIAL))
        val e1 = Gres3BenchmarkConfigHash.compute(sampleConfig(Gres3SenderExecutionModel.PER_TARGET_SOCKET_SEQUENTIAL))
        assertNotEquals(e0, e1)
    }

    @Test
    fun compute_differsBetweenE1AndE3() {
        val e1 = Gres3BenchmarkConfigHash.compute(sampleConfig(Gres3SenderExecutionModel.PER_TARGET_SOCKET_SEQUENTIAL))
        val e3 = Gres3BenchmarkConfigHash.compute(sampleConfig(Gres3SenderExecutionModel.SENDMMSG_BATCH))
        assertNotEquals(e1, e3)
    }

    @Test
    fun compute_differsBetweenE3AndE3b() {
        val e3 = Gres3BenchmarkConfigHash.compute(sampleConfig(Gres3SenderExecutionModel.SENDMMSG_BATCH))
        val e3b = Gres3BenchmarkConfigHash.compute(sampleConfig(Gres3SenderExecutionModel.SENDMMSG_BATCH_NONBLOCKING))
        assertNotEquals(e3, e3b)
    }

    @Test
    fun sendmmsg_socketCount_isOne() {
        assertEquals(1, Gres3SenderExecutionModel.SENDMMSG_BATCH.socketCount)
        assertEquals(1, Gres3SenderExecutionModel.SENDMMSG_BATCH_NONBLOCKING.socketCount)
    }
}
