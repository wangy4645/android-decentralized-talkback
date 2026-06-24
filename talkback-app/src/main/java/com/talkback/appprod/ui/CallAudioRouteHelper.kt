package com.talkback.appprod.ui

import android.content.Context
import android.media.AudioManager

enum class CallAudioRoute {
    SPEAKER,
    EARPIECE,
    HEADSET
}

object CallAudioRouteHelper {
    private var currentRoute: CallAudioRoute = CallAudioRoute.SPEAKER

    fun current(): CallAudioRoute = currentRoute

    fun apply(context: Context, route: CallAudioRoute) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        when (route) {
            CallAudioRoute.SPEAKER -> audioManager.isSpeakerphoneOn = true
            CallAudioRoute.EARPIECE -> audioManager.isSpeakerphoneOn = false
            CallAudioRoute.HEADSET -> {
                audioManager.isSpeakerphoneOn = false
                if (!audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            }
        }
        currentRoute = route
    }
}
