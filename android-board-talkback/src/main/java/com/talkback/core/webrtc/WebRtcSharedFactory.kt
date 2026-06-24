package com.talkback.core.webrtc

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single PeerConnectionFactory + AudioDeviceModule for the process.
 * Multiple ADMs on Android commonly break capture/playback.
 */
internal object WebRtcSharedFactory {
    private val lock = Any()
    private val refCount = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioConfigured = false
    private var pendingTeardown: Runnable? = null

    fun acquire(context: Context): PeerConnectionFactory {
        synchronized(lock) {
            pendingTeardown?.let { mainHandler.removeCallbacks(it) }
            pendingTeardown = null
            val appContext = context.applicationContext
            configureAndroidAudio(appContext)
            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()
                factory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .setAudioDeviceModule(requireNotNull(audioDeviceModule))
                    .createPeerConnectionFactory()
            }
            refCount.incrementAndGet()
            return requireNotNull(factory)
        }
    }

    fun release() {
        synchronized(lock) {
            if (refCount.decrementAndGet() > 0) return
            scheduleTeardown()
        }
    }

    private fun scheduleTeardown() {
        pendingTeardown?.let { mainHandler.removeCallbacks(it) }
        val teardown = Runnable {
            synchronized(lock) {
                if (refCount.get() > 0) return@synchronized
                pendingTeardown = null
                SharedLocalAudio.release()
                runCatching { factory?.dispose() }
                runCatching { audioDeviceModule?.release() }
                factory = null
                audioDeviceModule = null
                if (audioConfigured) {
                    resetAndroidAudio()
                    audioConfigured = false
                }
            }
        }
        pendingTeardown = teardown
        // Let WebRtcAudioTrack threads exit before tearing down the shared ADM (Huawei SIGABRT).
        mainHandler.postDelayed(teardown, 250L)
    }

    private fun configureAndroidAudio(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true
        audioConfigured = true
    }

    private fun resetAndroidAudio() {
        // Best-effort; context may be unavailable on last release.
    }
}
