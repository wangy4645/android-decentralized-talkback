package com.talkback.appprod.ui

import android.view.View
import android.widget.FrameLayout
import com.talkback.appprod.R

object CallAudioRouteUi {
    fun highlight(speakerRoot: View, headsetRoot: View, route: CallAudioRoute) {
        val speaker = speakerRoot.findViewById<FrameLayout>(R.id.btnControlCircle)
        val headset = headsetRoot.findViewById<FrameLayout>(R.id.btnControlCircle)
        speaker.setBackgroundResource(
            if (route == CallAudioRoute.SPEAKER) R.drawable.bg_call_control_circle_active
            else R.drawable.bg_call_control_circle
        )
        headset.setBackgroundResource(
            if (route == CallAudioRoute.EARPIECE || route == CallAudioRoute.HEADSET) {
                R.drawable.bg_call_control_circle_active
            } else {
                R.drawable.bg_call_control_circle
            }
        )
    }
}
