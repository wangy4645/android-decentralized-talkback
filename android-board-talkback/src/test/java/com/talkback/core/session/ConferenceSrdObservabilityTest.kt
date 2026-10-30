package com.talkback.core.session

import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceSrdObservabilityTest {

    @Test
    fun formatDispatch_includesRequiredFields() {
        val line = ConferenceSrdObservability.formatDispatch(
            ConferenceSrdObservability.Context(
                sessionId = "sess-1",
                remoteModuleId = "M04",
                localModuleId = "M01",
                pcGeneration = 4L
            )
        )
        assertTrue(line.startsWith("SRD_DISPATCH "))
        assertTrue(line.contains("session=sess-1"))
        assertTrue(line.contains("edgeKey=sess-1|M04"))
        assertTrue(line.contains("edge=M01->M04"))
        assertTrue(line.contains("remote=M04"))
        assertTrue(line.contains("pcGeneration=4"))
        assertTrue(line.contains("offerLineageId=OFFER_LINEAGE_UNKNOWN"))
        assertTrue(line.contains("executor=tb-edge-sess-1|M04"))
    }

    @Test
    fun formatExecutorEnter_includesQueueWaitMs() {
        val ctx = ConferenceSrdObservability.Context(
            sessionId = "sess-1",
            remoteModuleId = "M03",
            localModuleId = "M01",
            pcGeneration = 3L
        )
        val line = ConferenceSrdObservability.formatExecutorEnter(ctx, queueWaitMs = 12L)
        assertTrue(line.startsWith("SRD_EXECUTOR_ENTER "))
        assertTrue(line.contains("edge=M01->M03"))
        assertTrue(line.contains("queueWaitMs=12"))
    }
}
