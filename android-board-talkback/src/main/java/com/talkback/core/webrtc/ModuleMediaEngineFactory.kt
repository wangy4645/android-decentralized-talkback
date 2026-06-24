package com.talkback.core.webrtc

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * One WebRTC peer connection per remote module (module-level media topology).
 */
class ModuleMediaEngineFactory(
    private val context: Context,
    private val useStub: Boolean = false,
    private val onIceConnectionState: ((String, String) -> Unit)? = null
) {
    private val engines = ConcurrentHashMap<String, WebRtcAudioEngine>()

    fun getOrCreate(remoteModuleId: String): WebRtcAudioEngine {
        return engines.getOrPut(remoteModuleId) {
            if (useStub) {
                StubWebRtcAudioEngine()
            } else {
                RealWebRtcAudioEngine(context) { state ->
                    onIceConnectionState?.invoke(remoteModuleId, state)
                }
            }
        }
    }

    fun get(remoteModuleId: String): WebRtcAudioEngine? = engines[remoteModuleId]

    fun release(remoteModuleId: String) {
        engines.remove(remoteModuleId)?.release()
    }

    fun releaseAll() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
    }
}
