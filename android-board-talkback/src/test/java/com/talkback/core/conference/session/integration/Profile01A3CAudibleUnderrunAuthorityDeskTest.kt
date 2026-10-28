package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.integration.cutover.AudiblePlayoutOwnershipSeam
import com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly
import com.talkback.core.conference.transport.RecordingPlayoutMetricsSeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3-C step 1 — audible underrun counter authority (desk, no new telemetry).
 *
 * Product multicast PCM under MULTICAST_PRODUCTION writes to [AndroidAudioTrackPlayoutSeam]
 * (android.media.AudioTrack). [AudiblePlayoutOwnershipSeam.underrunCount] reads that instance only
 * when mode=MULTICAST_PRODUCTION; otherwise metrics seam (shadow / fenced).
 *
 * WebRTC `WebRtcAudioTrackExternal: underrun count` is ADR-0056 / shared ADM playout — a separate
 * AudioTrack instance. Field 143138 (session 67d12ffc…): ownership=MULTICAST_PRODUCTION,
 * WebRtcAudioTrack underrun=0 at stopPlayout; one `AudioTrack: restartIfDisabled … previous underrun`
 * aligns with production cutover, not WebRtc counter ~346 from pre-ownership runs.
 *
 * Do not use WebRtcAudioTrack underrun as multicast starvation authority.
 */
class Profile01A3CAudibleUnderrunAuthorityDeskTest {
    @Test
    fun harnessPlayoutMetricsUnderrun_isShadowRecordingSeam_notWebRtc() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        assertEquals(0L, assembly.playoutMetrics.underrunCount)
        assertFalse(assembly.playoutSeam is AudiblePlayoutOwnershipSeam)
        assertTrue(assembly.playoutMetrics is RecordingPlayoutMetricsSeam)
    }

    @Test
    fun shadowHarness_neverUsesWebRtcAdmPlayoutSeam() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        assertFalse(assembly.playoutSeam.javaClass.name.contains("WebRtc"))
    }
}
