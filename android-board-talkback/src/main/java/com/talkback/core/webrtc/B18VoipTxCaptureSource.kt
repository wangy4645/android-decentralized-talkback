package com.talkback.core.webrtc

import android.media.MediaRecorder
import android.os.Build

/**
 * B-18: M02-only AudioSource.MIC so AudioPolicy can leave voip_tx.
 * There is no app API to keep VOICE_COMMUNICATION and still select primary input.
 * HW AEC/NS remain requested true. M01/M03/M04 unchanged.
 */
object B18VoipTxCaptureSource {
    /** M02 field board; not M04 (DSJ_A7S). */
    fun isM02Board(model: String = Build.MODEL): Boolean = model == "g80l_221_v1_fhd"

    fun audioSource(model: String = Build.MODEL): Int =
        if (isM02Board(model)) {
            MediaRecorder.AudioSource.MIC
        } else {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
}
