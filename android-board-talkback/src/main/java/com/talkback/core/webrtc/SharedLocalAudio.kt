package com.talkback.core.webrtc

import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory

/**
 * One local microphone capture shared by all PeerConnections in the process.
 * Avoids multiple AudioSource instances competing in mesh conferences.
 */
internal object SharedLocalAudio {
    private val lock = Any()
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null

    fun acquireLocalTrack(factory: PeerConnectionFactory): AudioTrack {
        synchronized(lock) {
            if (localTrack == null) {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                }
                audioSource = factory.createAudioSource(constraints)
                localTrack = factory.createAudioTrack("tb_shared_audio", requireNotNull(audioSource))
                localTrack?.setEnabled(false)
            }
            return requireNotNull(localTrack)
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { localTrack?.setEnabled(false) }
            runCatching { localTrack?.dispose() }
            runCatching { audioSource?.dispose() }
            localTrack = null
            audioSource = null
        }
    }
}
