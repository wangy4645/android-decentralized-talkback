package com.talkback.core.webrtc

import com.talkback.core.conference.runtime.OpusCodecConstants
import org.junit.Assert.assertFalse
import org.junit.Test

class MulticastApmReverseFeedDeskTest {
    @Test
    fun submitPlayoutPcm_rejectsWrongFrameSize() {
        MulticastApmReverseFeed.enabled = true
        assertFalse(MulticastApmReverseFeed.submitPlayoutPcm(ShortArray(100)))
        MulticastApmReverseFeed.enabled = false
    }

    @Test
    fun submitPlayoutPcm_whenDisabled_noOp() {
        MulticastApmReverseFeed.enabled = false
        assertFalse(
            MulticastApmReverseFeed.submitPlayoutPcm(
                ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS),
            ),
        )
    }
}
