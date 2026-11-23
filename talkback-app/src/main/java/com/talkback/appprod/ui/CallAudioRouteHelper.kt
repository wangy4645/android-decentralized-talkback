package com.talkback.appprod.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

enum class CallAudioRoute {
    SPEAKER,
    EARPIECE,
    HEADSET
}

object CallAudioRouteHelper {
    private const val TAG = "CallAudioRoute"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentRoute: CallAudioRoute = CallAudioRoute.SPEAKER
    private var scoReceiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    private val scoRetries = listOf(200L, 500L, 1200L, 2500L)
    private var retryToken = 0

    fun current(): CallAudioRoute = currentRoute

    fun preferred(context: Context): CallAudioRoute {
        ensureAttached(context)
        val audioManager = audioManager(context)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return if (headsetAvailable(audioManager)) CallAudioRoute.HEADSET else CallAudioRoute.SPEAKER
    }

    fun preferredNonSpeaker(context: Context): CallAudioRoute {
        ensureAttached(context)
        val audioManager = audioManager(context)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return if (headsetAvailable(audioManager)) CallAudioRoute.HEADSET else CallAudioRoute.EARPIECE
    }

    fun apply(context: Context, route: CallAudioRoute) {
        ensureAttached(context)
        val audioManager = audioManager(context)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        retryToken += 1
        @Suppress("DEPRECATION")
        when (route) {
            CallAudioRoute.SPEAKER -> {
                stopHeadset(audioManager)
                if (!setCommunicationDeviceOfTypes(audioManager, listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))) {
                    audioManager.isSpeakerphoneOn = true
                }
            }
            CallAudioRoute.EARPIECE -> {
                stopHeadset(audioManager)
                audioManager.isSpeakerphoneOn = false
                setCommunicationDeviceOfTypes(audioManager, listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
            }
            CallAudioRoute.HEADSET -> {
                audioManager.isSpeakerphoneOn = false
                startSco(audioManager)
                bindHeadsetDevice(audioManager)
                scheduleHeadsetRetries()
            }
        }
        currentRoute = route
        logState(audioManager, "apply")
    }

    fun headsetAvailable(context: Context): Boolean = headsetAvailable(audioManager(context))

    private fun headsetAvailable(audioManager: AudioManager): Boolean {
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn || audioManager.isWiredHeadsetOn) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (audioManager.availableCommunicationDevices.any { it.type in headsetTypes }) return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes }) {
                return true
            }
        }
        @Suppress("DEPRECATION")
        return audioManager.isBluetoothA2dpOn
    }

    private fun startSco(audioManager: AudioManager) {
        @Suppress("DEPRECATION")
        runCatching {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun bindHeadsetDevice(audioManager: AudioManager) {
        val bound = setCommunicationDeviceOfTypes(audioManager, headsetTypes.toList())
        logState(audioManager, if (bound) "bind-ok" else "bind-miss")
    }

    private fun scheduleHeadsetRetries() {
        val token = retryToken
        scoRetries.forEach { delayMs ->
            mainHandler.postDelayed({
                if (token != retryToken || currentRoute != CallAudioRoute.HEADSET) return@postDelayed
                val context = appContext ?: return@postDelayed
                bindHeadsetDevice(audioManager(context))
            }, delayMs)
        }
    }

    private fun stopHeadset(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        @Suppress("DEPRECATION")
        runCatching {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }

    private fun setCommunicationDeviceOfTypes(audioManager: AudioManager, types: List<Int>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type in types }
            ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    private fun ensureAttached(context: Context) {
        if (scoReceiver != null) return
        val app = context.applicationContext
        appContext = app
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (currentRoute != CallAudioRoute.HEADSET) return
                val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, Int.MIN_VALUE)
                val headsetAudio = intent.getIntExtra("android.bluetooth.profile.extra.STATE", Int.MIN_VALUE)
                val scoConnected = scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED ||
                    headsetAudio == 12
                if (!scoConnected && scoState == Int.MIN_VALUE && headsetAudio == Int.MIN_VALUE) return
                if (scoState == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) return
                bindHeadsetDevice(audioManager(app))
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            scoReceiver = receiver
        }.onFailure {
            Log.w(TAG, "sco receiver not registered", it)
        }
    }

    private fun logState(audioManager: AudioManager, stage: String) {
        @Suppress("DEPRECATION")
        val comm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.joinToString(",") { typeName(it.type) }
        } else {
            "-"
        }
        Log.i(
            TAG,
            "$stage route=$currentRoute speaker=${audioManager.isSpeakerphoneOn} " +
                "sco=${audioManager.isBluetoothScoOn} devices=$comm",
        )
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_hs"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_hp"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_hs"
        else -> type.toString()
    }

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val headsetTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )
}