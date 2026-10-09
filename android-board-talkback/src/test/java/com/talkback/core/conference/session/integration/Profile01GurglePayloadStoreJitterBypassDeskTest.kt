package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.wire.WireIngressResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GURGLE-DESK (3) / P3 — jitter-rejected frames must not decode into mix via [OpusPayloadStore].
 */
class Profile01GurglePayloadStoreJitterBypassDeskTest {
    @Test
    fun reorderRejectedFrame_notInStore_andCannotDecodeAtMixCycle() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        val fixture =
            Phase1MediaHarness.threeSourceFixtures.first().copy(sourceIdentity = "M01")
        Phase1MediaHarness.installAuthority(assembly.orchestrator.authority.store, listOf(fixture))
        assembly.orchestrator.authority.syncAdmittedToRuntime(fixture.sourceIdentity)

        val baseSlot = 100
        val jumpSlot = baseSlot + 6
        val rxWallMs = 1_000L
        val pipeline = assembly.pipeline
        val store = assembly.opusPayloadStore

        val first =
            pipeline.admitProtectedDatagram(
                fixture.sourceIdentity,
                Phase1MediaHarness.buildToneProtectedPacket(fixture, baseSlot),
                rxWallMs,
            )
        assertTrue(first.ingress is WireIngressResult.Accepted)
        assertEquals(FrameAdmitDisposition.QUEUED, first.frameAdmit)

        val reorder =
            pipeline.admitProtectedDatagram(
                fixture.sourceIdentity,
                Phase1MediaHarness.buildToneProtectedPacket(fixture, jumpSlot),
                rxWallMs + 5,
            )
        assertTrue(reorder.ingress is WireIngressResult.Accepted)
        assertEquals(FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED, reorder.frameAdmit)

        assertNull(
            store.peek(fixture.sourceIdentity, fixture.incarnationId, jumpSlot.toLong()),
        )
        assertNull(
            pipeline.orchestrator.pipeline.peekBufferedFrame(
                fixture.sourceIdentity,
                fixture.incarnationId,
                jumpSlot.toLong(),
            ),
        )

        val decodeBefore = assembly.decoderSeam.decodeSuccessCount
        val mix =
            pipeline.executeTimedMixCycle(
                nowMs = rxWallMs + 400,
                slot = jumpSlot.toLong(),
                slotMediaTimeMs = jumpSlot.toLong() * 20L,
            )
        val pull = mix.mixCycle.sourcePullDispositions[fixture.sourceIdentity]
        assertTrue(
            "jitter pull must not be DECODE_FRAME when frame was reorder-rejected (got $pull)",
            pull != SlotPullDisposition.DECODE_FRAME,
        )
        assertEquals(
            "P3: reorder-rejected payload must not decode via store",
            decodeBefore,
            assembly.decoderSeam.decodeSuccessCount,
        )
        assertFalse(
            fixture.sourceIdentity in mix.mixCycle.mixParticipantIdentities,
        )
    }
}
