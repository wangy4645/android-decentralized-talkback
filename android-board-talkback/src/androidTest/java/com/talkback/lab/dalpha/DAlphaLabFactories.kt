package com.talkback.lab.dalpha

import android.content.Context
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * D-α lab-only: construct 2 [PeerConnectionFactory] instances sharing one [JavaAudioDeviceModule].
 * NOT used in production. See conference-srd-d-alpha-lab-spike-authorization-001.md
 */
internal object DAlphaLabFactories {

    data class Pair(
        val audioDeviceModule: JavaAudioDeviceModule,
        val factoryA: PeerConnectionFactory,
        val factoryB: PeerConnectionFactory,
    ) {
        val factoriesDistinct: Boolean
            get() = factoryA !== factoryB
    }

    fun create(context: Context): Pair {
        val appContext = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val factoryA = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        val factoryB = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        return Pair(adm, factoryA, factoryB)
    }

    fun dispose(pair: Pair, disposeAdm: Boolean = false) {
        runCatching { pair.factoryA.dispose() }
        runCatching { pair.factoryB.dispose() }
        if (disposeAdm) {
            runCatching { pair.audioDeviceModule.release() }
        }
    }
}
