package com.talkback.core.webrtc

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import com.talkback.core.media.MediaObservabilityLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Single PeerConnectionFactory + AudioDeviceModule for the process.
 * Multiple ADMs on Android commonly break capture/playback.
 */
internal object WebRtcSharedFactory {
    private val lock = Any()
    /**
     * G2-RCA2: serialize shared-factory signaling mutations across all PeerConnections.
     * SRD ANSWER holds through callback completion; addIceCandidate uses the same fence.
     */
    private val sdpApplyMutex = ReentrantLock(true)
    private val refCount = AtomicInteger(0)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioConfigured = false
    private var pendingTeardown: Runnable? = null
    private val localOutboundSinks = CopyOnWriteArrayList<LocalOutboundPcmSink>()

    fun addLocalOutboundSink(sink: LocalOutboundPcmSink): () -> Unit {
        localOutboundSinks.add(sink)
        return { localOutboundSinks.remove(sink) }
    }

    fun <T> withSrdApplyLock(block: () -> T): T {
        sdpApplyMutex.lock()
        try {
            return block()
        } finally {
            sdpApplyMutex.unlock()
        }
    }

    fun acquire(context: Context): PeerConnectionFactory {
        synchronized(lock) {
            pendingTeardown?.let { mainHandler.removeCallbacks(it) }
            pendingTeardown = null
            val appContext = context.applicationContext
            configureAndroidAudio(appContext)
            if (factory == null) {
                WebRtcNetworkBridgeInstall.installBeforeWebRtcInit()
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .setSamplesReadyCallback(::dispatchLocalOutboundPcm)
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

    fun release(observedModuleId: String? = null) {
        val moduleTag = observedModuleId ?: "shared"
        synchronized(lock) {
            MediaObservabilityLog.sharedFactoryReleaseEnter(moduleTag)
            if (refCount.decrementAndGet() > 0) {
                MediaObservabilityLog.sharedFactoryReleaseExit(moduleTag, stage = "refcount")
                return
            }
            scheduleTeardown(moduleTag)
        }
    }

    private fun scheduleTeardown(moduleTag: String) {
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
                MediaObservabilityLog.sharedFactoryReleaseExit(moduleTag, stage = "teardown")
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

    private fun dispatchLocalOutboundPcm(samples: JavaAudioDeviceModule.AudioSamples) {
        if (localOutboundSinks.isEmpty()) return
        if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) return
        val channels = samples.channelCount
        if (channels <= 0) return
        val bytesPerFrame = 2 * channels
        if (bytesPerFrame <= 0 || samples.data.size < bytesPerFrame) return
        val frames = samples.data.size / bytesPerFrame
        val buffer = ByteBuffer.wrap(samples.data).order(ByteOrder.LITTLE_ENDIAN)
        localOutboundSinks.forEach { sink ->
            sink.onPcm(buffer.duplicate(), 16, samples.sampleRate, channels, frames)
        }
    }
}
