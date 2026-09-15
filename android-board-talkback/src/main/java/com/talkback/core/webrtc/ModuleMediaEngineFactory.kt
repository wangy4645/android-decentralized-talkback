package com.talkback.core.webrtc

import android.content.Context
import com.talkback.core.media.MediaObservabilityLog
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

    fun getOrCreate(remoteModuleId: String): WebRtcAudioEngine =
        getOrCreateDetailed(remoteModuleId).engine

    fun getOrCreateDetailed(remoteModuleId: String): EngineFactoryProvision {
        var factoryHit = FactoryHit.REUSE
        val engine = engines.getOrPut(remoteModuleId) {
            factoryHit = FactoryHit.NEW
            if (useStub) {
                StubWebRtcAudioEngine(observedModuleId = remoteModuleId)
            } else {
                RealWebRtcAudioEngine(context, observedModuleId = remoteModuleId) { state ->
                    onIceConnectionState?.invoke(remoteModuleId, state)
                }
            }
        }
        return EngineFactoryProvision(engine, factoryHit)
    }

    fun get(remoteModuleId: String): WebRtcAudioEngine? = engines[remoteModuleId]

    fun release(remoteModuleId: String) {
        MediaObservabilityLog.releaseEnter(remoteModuleId)
        val engine = engines.remove(remoteModuleId)
        if (engine == null) {
            MediaObservabilityLog.pcCloseSkipped(remoteModuleId, "factoryMiss")
            return
        }
        engine.release()
    }

    fun releaseAll() {
        engines.keys.toList().forEach { release(it) }
    }
}
