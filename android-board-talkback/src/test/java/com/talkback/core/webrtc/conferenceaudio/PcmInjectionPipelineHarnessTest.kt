package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1a-2 — source → mixer → injection port harness. */
class PcmInjectionPipelineHarnessTest {

    @Test
    fun remoteSourceThroughMixerToPort_withoutCoordinatorOrWebRtc() {
        val harness = PcmInjectionPipelineHarness(targetModuleId = "M02")
        assertTrue(harness.admitTarget().isSuccess)
        harness.admitSource("M03", level = 5_000)

        assertTrue(harness.pushAndInject("M03").isSuccess)
        assertEquals(1, harness.injectionPort.writtenFrames.size)
        assertTrue(harness.injectionPort.writtenFrames.first().peakAbs() > 0)
    }

    @Test
    fun injectBeforeTargetAdmitted_rejected() {
        val harness = PcmInjectionPipelineHarness(targetModuleId = "M02")
        harness.admitSource("M03")

        val result = harness.pushAndInject("M03")
        assertTrue(result.isFailure)
        assertEquals(0, harness.injectionPort.writtenFrames.size)
        assertTrue(
            harness.recordedEvents.any { it is PcmInjectionPortEvent.InjectRejected }
        )
    }

    @Test
    fun sourceRemovalThenRevoke_stopsInjection() {
        val harness = PcmInjectionPipelineHarness(targetModuleId = "M02")
        harness.admitTarget()
        harness.admitSource("M03")
        assertTrue(harness.pushAndInject("M03").isSuccess)

        harness.removeSource("M03")
        harness.revokeTarget(PcmInjectionPortSession.RevokeReason.TARGET_REMOVED)

        assertEquals(PcmInjectionPortSession.State.REVOKED, harness.portSession.lifecycleState)
        assertTrue(harness.pushAndInject("M03").isFailure)
    }

    @Test
    fun twoSources_mixThenInject() {
        val harness = PcmInjectionPipelineHarness(targetModuleId = "M02")
        harness.admitTarget()
        harness.admitSource("M03", level = 3_000)
        harness.admitSource("M04", level = 6_000)

        harness.audioMixer.push("M03", PcmFrame.constantLevel(3_000))
        harness.audioMixer.push("M04", PcmFrame.constantLevel(6_000))
        assertTrue(harness.renderAndInject().isSuccess)

        val singleSourcePeak = PcmInjectionPipelineHarness(
            targetModuleId = "M02"
        ).apply {
            admitTarget()
            admitSource("M03", level = 3_000)
            pushAndInject("M03")
        }.injectionPort.writtenFrames.first().peakAbs()

        assertTrue(harness.injectionPort.writtenFrames.first().peakAbs() > singleSourcePeak)
    }

    @Test
    fun portInjectFailure_observableInSessionEvents() {
        val port = RecordingPcmInjectionPort()
        val harness = PcmInjectionPipelineHarness(targetModuleId = "M02", port = port)
        harness.admitTarget()
        harness.admitSource("M03")
        port.injectNextFailure(PcmInjectionFailure.INJECT_FAILED)

        assertTrue(harness.pushAndInject("M03").isFailure)
        assertEquals(PcmInjectionPortSession.State.FAILED, harness.portSession.lifecycleState)
        assertTrue(harness.recordedEvents.any { it is PcmInjectionPortEvent.InjectFailed })
    }
}
