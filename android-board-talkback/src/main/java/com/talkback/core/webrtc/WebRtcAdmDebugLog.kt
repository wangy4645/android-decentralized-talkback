package com.talkback.core.webrtc

import com.talkback.core.util.TalkbackLog
import org.webrtc.Loggable
import org.webrtc.Logging

/**
 * B-12/B-16 — forward existing WebRTC ADM debug to Talkback logcat.
 * Does not change AudioRecord source, HW AEC/NS flags, routing, or PCM.
 * Production AAR uses tag WebRtcAudioRecordExternal (not WebRtcAudioRecord).
 */
object WebRtcAdmDebugLog {
    const val PREFIX = "WEBRTC_ADM"

    private val tags =
        setOf(
            "JavaAudioDeviceModule",
            "WebRtcAudioRecord",
            "WebRtcAudioRecordExternal",
            "WebRtcAudioEffectsExternal",
        )

    fun shouldForward(tag: String): Boolean = tag in tags

    val loggable: Loggable =
        Loggable { message, _, tag ->
            if (shouldForward(tag)) {
                TalkbackLog.i("$PREFIX tag=$tag $message")
            }
        }

    val severity: Logging.Severity = Logging.Severity.LS_INFO
}
