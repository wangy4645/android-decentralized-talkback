package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * P3 — when jitter did not authorize DECODE_FRAME, store payload must not produce REAL decode.
 */
class Profile01P3PlcSilenceNoStoreDecodeDeskTest {
    @Test
    fun orphanStorePayload_withoutJitterFrame_doesNotRealDecode() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        val fixture =
            Phase1MediaHarness.threeSourceFixtures.first().copy(sourceIdentity = "M01")
        Phase1MediaHarness.installAuthority(assembly.orchestrator.authority.store, listOf(fixture))
        assembly.orchestrator.authority.syncAdmittedToRuntime(fixture.sourceIdentity)
        assembly.orchestrator.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = fixture.sourceIdentity,
                incarnationId = fixture.incarnationId,
                voiceActive = true,
                audioLevel = 90,
            ),
        )

        val slot = 2_000
        val rxWallMs = 5_000L
        val pipeline = assembly.pipeline
        val store = assembly.opusPayloadStore
        val packet = Phase1MediaHarness.buildToneProtectedPacket(fixture, slot)

        store.put(fixture.sourceIdentity, fixture.incarnationId, slot.toLong(), packet)

        assembly.orchestrator.selectTopK(rxWallMs)
        val lateNow =
            rxWallMs +
                MediaJitterConstants.MAX_PLAYOUT_DELAY_MS +
                MediaJitterConstants.MEDIA_SLOT_MS
        val decodeBefore = assembly.decoderSeam.decodeSuccessCount
        val mix =
            pipeline.executeTimedMixCycle(
                nowMs = lateNow,
                slot = slot.toLong(),
                slotMediaTimeMs = slot.toLong() * MediaJitterConstants.MEDIA_SLOT_MS,
            )
        val pull = mix.mixCycle.sourcePullDispositions[fixture.sourceIdentity]
        assertNotEquals(SlotPullDisposition.DECODE_FRAME, pull)
        assertEquals(decodeBefore, assembly.decoderSeam.decodeSuccessCount)
    }
}
