package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.SlotPullDisposition
import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePullEmptyDiagnosticsDeskTest {
    private val pipeline = MediaExecutionPipeline()

    @Test
    fun fencedSkip_mapsToMembershipMismatch() {
        val reason =
            SourcePullEmptyDiagnostics.classifyAfterPull(
                sourceIdentity = "M04",
                incarnationId = 1L,
                sessionId = "s1",
                pullSlot = 100L,
                slotMediaTimeMs = 2000L,
                nowMs = 2100L,
                disposition = SlotPullDisposition.FENCED_SKIP,
                peekedBeforePull = null,
                pipeline = pipeline,
                decodeEligible = true,
            ).emptyReason
        assertEquals(SourcePullEmptyReason.MEMBERSHIP_OR_BINDING_MISMATCH, reason)
    }

    @Test
    fun plc_mapsToPullPlc() {
        val reason =
            SourcePullEmptyDiagnostics.classifyAfterPull(
                sourceIdentity = "M04",
                incarnationId = 1L,
                sessionId = "s1",
                pullSlot = 100L,
                slotMediaTimeMs = 2000L,
                nowMs = 2500L,
                disposition = SlotPullDisposition.PLC_SYNTHESIS,
                peekedBeforePull = null,
                pipeline = pipeline,
                decodeEligible = true,
            ).emptyReason
        assertEquals(SourcePullEmptyReason.PULL_PLC, reason)
    }
}
