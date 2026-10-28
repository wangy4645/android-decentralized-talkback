package com.talkback.core.conference.transport

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.runtime.SlotPullDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RX REAL path: Opus staging and decode must follow jitter [QUEUED] + [DECODE_FRAME] pull.
 */
class ConferenceMulticastOpusStagingGateTest {
    @Test
    fun lateForPlayout_doesNotStageOpus() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        val fixture = Phase1MediaHarness.threeSourceFixtures.first()
        Phase1MediaHarness.installAuthority(
            assembly.orchestrator.authority.store,
            listOf(fixture),
        )
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val mediaSlot = 99L
        val mediaTime = 5_000L
        val arrival = mediaTime + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
        val datagram = Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot.toInt())
        val admit =
            assembly.pipeline.admitProtectedDatagram(
                fixture.sourceIdentity,
                datagram,
                arrival,
            )
        assertEquals(FrameAdmitDisposition.LATE_FOR_PLAYOUT, admit.frameAdmit)
        assertEquals(0, assembly.opusPayloadStore.size())
    }

    @Test
    fun mixCycle_skipsDecodeUnlessPullReturnedDecodeFrame() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        val fixture = Phase1MediaHarness.threeSourceFixtures.first()
        Phase1MediaHarness.installAuthority(
            assembly.orchestrator.authority.store,
            listOf(fixture),
        )
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val slot = 10L
        val mediaTime = 1_000L
        assembly.opusPayloadStore.put(
            fixture.sourceIdentity,
            fixture.incarnationId,
            slot,
            OpusTestVectors.encodeTone(),
        )

        val now = mediaTime + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5
        val mix = assembly.pipeline.executeTimedMixCycle(now, slot, mediaTime)
        assertTrue(mix.mixCycle.mixParticipantIdentities.isEmpty())
        assertNull(mix.mixCycle.sourceMixInputs[fixture.sourceIdentity])
        assertEquals(1, assembly.opusPayloadStore.size())
    }
}
