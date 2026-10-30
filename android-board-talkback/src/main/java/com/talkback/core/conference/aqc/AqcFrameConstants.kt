package com.talkback.core.conference.aqc

import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.OpusCodecConstants

object AqcFrameConstants {
    const val FRAME_SAMPLES_20MS: Int = OpusCodecConstants.FRAME_SAMPLES_20MS
    const val SAMPLE_RATE_HZ: Int = MediaMixConstants.SAMPLE_RATE_HZ
    const val CLIP_ABS_THRESHOLD: Int = 29_000
    val OUTPUT_PEAK_ABS_FS: Double = MediaMixConstants.OUTPUT_PEAK_ABS_FS
}
