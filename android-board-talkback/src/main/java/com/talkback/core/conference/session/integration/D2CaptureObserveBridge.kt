package com.talkback.core.conference.session.integration

import android.util.Log
import com.talkback.core.webrtc.CapturePcmLevelDiagnostic

/** Routes S1 APM window diagnostics into [MeetingProductMediaShadow.LOG_TAG]. */
object D2CaptureObserveBridge {
    fun formatApmCaptureShadowLine(detail: String): String =
        "PROFILE01_SHADOW_RUNTIME phase=D2_APM_CAPTURE txPath=PRE_APM $detail"

    fun installIfNeeded() {
        if (!D2SendChainRuntime.captureChainObserveEnabled) {
            CapturePcmLevelDiagnostic.fieldObserveLineSink = null
            return
        }
        CapturePcmLevelDiagnostic.fieldObserveLineSink = { detail ->
            Log.i(MeetingProductMediaShadow.LOG_TAG, formatApmCaptureShadowLine(detail))
        }
    }

    fun clear() {
        CapturePcmLevelDiagnostic.fieldObserveLineSink = null
    }
}
