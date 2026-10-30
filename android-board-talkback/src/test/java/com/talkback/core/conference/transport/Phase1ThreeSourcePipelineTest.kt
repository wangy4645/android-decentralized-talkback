package com.talkback.core.conference.transport

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.wire.WireIngressResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 Slice 3 — 3 active sources through real Opus decode → mix → playout observation.
 */
class Phase1ThreeSourcePipelineTest {
    @Test
    fun threeSources_realOpus_decodeMix_reachesPlayoutObservation() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        val store = assembly.orchestrator.authority.store
        val fixtures = Phase1MediaHarness.threeSourceFixtures
        Phase1MediaHarness.installAuthority(store, fixtures)
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val mediaSlot = 0x1001
        val rxWallMs = 20_000L
        val protected =
            fixtures.associate { fixture ->
                fixture.sourceIdentity to
                    Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
            }

        val playout =
            assembly.pipeline.processThreeSourceSlot(
                fixtures = fixtures,
                protectedBySource = protected,
                rxWallMs = rxWallMs,
                mediaSlot = mediaSlot,
            )

        assertEquals(setOf("S1", "S2", "S3"), playout.mixCycle.topKIdentities)
        assertEquals(setOf("S1", "S2", "S3"), playout.mixCycle.decodeInvocationIdentities)
        assertEquals(setOf("S1", "S2", "S3"), playout.mixCycle.mixParticipantIdentities)
        assertEquals(3, playout.mixCycle.mixedBlock.mixParticipantCount)
        assertTrue(playout.mixCycle.mixedBlock.samples.size == MediaMixConstants.SAMPLE_RATE_HZ / 50)
        assertTrue(playout.playoutObserved)
        assertEquals(3L, assembly.decoderSeam.decodeSuccessCount)
        assertTrue(playout.decodeDurationUs >= 0L)
        assertTrue(playout.mixDurationUs >= 0L)
        assertTrue(playout.playoutBudgetUsedUs >= playout.decodeDurationUs)
        assertEquals(1L, assembly.playoutMetrics.successfulWrites)

        val snap = assembly.pipeline.observability.snapshot()
        assertEquals(3L, snap.ingressAccepted)
        assertTrue(snap.decodeDurationUs >= 0L)
        assertTrue(snap.mixDurationUs >= 0L)
        assertTrue(snap.playoutBudgetUsedUs >= 0L)
        for (fixture in fixtures) {
            assertEquals(1, snap.perSourceJitterDepth[fixture.sourceIdentity])
        }
    }

    @Test
    fun threeSourceAdmit_eachIngressAcceptedAndQueued() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        Phase1MediaHarness.installAuthority(
            assembly.orchestrator.authority.store,
            Phase1MediaHarness.threeSourceFixtures,
        )
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val mediaSlot = 42
        val rxWallMs = 850L
        for (fixture in Phase1MediaHarness.threeSourceFixtures) {
            val datagram = Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
            val admit = assembly.pipeline.admitProtectedDatagram(fixture.sourceIdentity, datagram, rxWallMs)
            assertTrue(admit.ingress is WireIngressResult.Accepted)
            assertEquals(FrameAdmitDisposition.QUEUED, admit.frameAdmit)
            assertEquals(1, admit.jitterDepth)
        }
        assertEquals(3, assembly.opusPayloadStore.size())
    }
}
