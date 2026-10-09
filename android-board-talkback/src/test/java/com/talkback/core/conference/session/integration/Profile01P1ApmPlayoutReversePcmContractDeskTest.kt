package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.conference.session.integration.cutover.AudiblePlayoutOwnershipSeam
import com.talkback.core.webrtc.MulticastApmReverseFeed
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * P1-b — same mixed PCM block feeds APM reverse reference and AudioTrack in one write().
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Profile01P1ApmPlayoutReversePcmContractDeskTest {
    private lateinit var seam: AudiblePlayoutOwnershipSeam
    private val trackBlock = AtomicReference<MixedBlock>()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        seam = AudiblePlayoutOwnershipSeam(context, "p1-apm-playout-contract")
        seam.deskTestOnProductionWrite = { trackBlock.set(it) }
        MulticastApmReverseFeed.enabled = true
        MulticastApmReverseFeed.clearDeskTestCapture()
        assertTrue(seam.acquireProductionAudioTrack())
    }

    @After
    fun tearDown() {
        seam.deskTestOnProductionWrite = null
        seam.releaseProductionAudioTrack()
        MulticastApmReverseFeed.clearDeskTestCapture()
        MulticastApmReverseFeed.enabled = false
    }

    @Test
    fun multicastProduction_write_usesIdenticalPcmForReverseAndAudioTrack() {
        val samples =
            ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { index ->
                ((index % 97) - 48).toShort()
            }
        val block =
            MixedBlock(
                samples = samples,
                mixParticipantCount = 1,
                peakAbsFs = 0.5,
            )
        seam.write(block, nowMs = 1_700_000_000_000L)

        val apmPcm = MulticastApmReverseFeed.deskTestLastSubmittedPcm
        assertArrayEquals(samples, apmPcm)
        val track = trackBlock.get()
        assertArrayEquals(samples, track.samples)
        assertArrayEquals(apmPcm, track.samples)
    }
}
