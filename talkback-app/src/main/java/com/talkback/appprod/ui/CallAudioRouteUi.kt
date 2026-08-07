package com.talkback.appprod.ui

import android.view.View
import android.widget.FrameLayout
import com.talkback.appprod.R

object CallAudioRouteUi {
    fun highlight(speakerRoot: View, headsetRoot: View, route: CallAudioRoute) {
        highlightSpeaker(speakerRoot, route == CallAudioRoute.SPEAKER)
        highlightControl(
            headsetRoot,
            route == CallAudioRoute.EARPIECE || route == CallAudioRoute.HEADSET
        )
    }

    fun highlightSpeaker(speakerRoot: View, active: Boolean) {
        highlightControl(speakerRoot, active)
    }

    fun highlightControl(root: View, active: Boolean) {
        val circle = root.findViewById<FrameLayout>(R.id.btnControlCircle) ?: return
        circle.setBackgroundResource(
            if (active) R.drawable.bg_call_control_circle_active
            else R.drawable.bg_call_control_circle
        )
    }
}
