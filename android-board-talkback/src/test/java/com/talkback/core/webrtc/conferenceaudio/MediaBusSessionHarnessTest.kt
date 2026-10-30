package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1a-3 — star topology bus harness. */
class MediaBusSessionHarnessTest {

    @Test
    fun starTopology_pushRender_deliversToOtherTargets() {
        val harness = MediaBusSessionHarness(
            targetIds = listOf("M02", "M03"),
            sourceIds = listOf("M02", "M03")
        )
        harness.fakeSource("M02", level = 5_000)
        assertTrue(harness.pushAndRender("M02").isSuccess)
        assertEquals(1, harness.portWrittenFrames("M03").size)
        assertEquals(0, harness.portWrittenFrames("M02").size)
    }

    @Test
    fun localMicIncluded_mixesIntoAllTargets() {
        val harness = MediaBusSessionHarness(
            targetIds = listOf("M02", "M03"),
            sourceIds = listOf("M02", "M03"),
            includeLocalMic = true
        )
        harness.fakeSource(ParticipantMediaModePolicy.LOCAL_MIC_SOURCE_ID, level = 2_000)
        assertTrue(
            harness.session.pushAndRender(
                MediaEdgeId(ParticipantMediaModePolicy.LOCAL_MIC_SOURCE_ID),
                PcmFrame.constantLevel(2_000)
            ).isSuccess
        )
        assertEquals(1, harness.portWrittenFrames("M02").size)
        assertEquals(1, harness.portWrittenFrames("M03").size)
    }

    @Test
    fun sourceRevoke_drainRamp_rejectsFurtherPushFromRevokedSource() {
        val harness = MediaBusSessionHarness(
            targetIds = listOf("M02", "M03"),
            sourceIds = listOf("M02", "M03")
        )
        harness.fakeSource("M02", level = 8_000)
        harness.fakeSource("M03", level = 3_000)
        assertTrue(harness.pushAndRender("M02").isSuccess)
        harness.session.revokeSource(MediaEdgeId("M02"))
        assertTrue(harness.pushAndRender("M02").isFailure)
        assertTrue(harness.pushAndRender("M03").isSuccess)
    }
}
