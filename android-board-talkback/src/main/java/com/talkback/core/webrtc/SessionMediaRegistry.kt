package com.talkback.core.webrtc

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Mesh (group/conference) reuses [ModuleMediaEngineFactory]; unicast uses a separate PC per call.
 */
class SessionMediaRegistry(
    context: Context,
    private val useStub: Boolean = false,
    onMeshIce: (String, String) -> Unit,
    private val onUnicastIce: (String, String) -> Unit
) {
    private val appContext = context.applicationContext
    private val meshFactory = ModuleMediaEngineFactory(appContext, useStub, onMeshIce)
    private val unicastEngines = ConcurrentHashMap<String, WebRtcAudioEngine>()

    fun groupEngine(remoteModuleId: String): WebRtcAudioEngine =
        meshFactory.getOrCreate(remoteModuleId)

    fun getGroup(remoteModuleId: String): WebRtcAudioEngine? = meshFactory.get(remoteModuleId)

    fun releaseGroup(remoteModuleId: String) {
        meshFactory.release(remoteModuleId)
    }

    fun conferenceEngine(remoteModuleId: String): WebRtcAudioEngine =
        meshFactory.getOrCreate(remoteModuleId)

    fun unicastEngine(sessionId: String): WebRtcAudioEngine =
        unicastEngines.getOrPut(sessionId) { createUnicastEngine(sessionId, onUnicastIce) }

    fun getUnicast(sessionId: String): WebRtcAudioEngine? = unicastEngines[sessionId]

    fun releaseUnicast(sessionId: String) {
        unicastEngines.remove(sessionId)?.release()
    }

    fun releaseAll() {
        meshFactory.releaseAll()
        unicastEngines.values.forEach { runCatching { it.release() } }
        unicastEngines.clear()
    }

    private fun createUnicastEngine(
        sessionId: String,
        onIce: (String, String) -> Unit
    ): WebRtcAudioEngine {
        if (useStub) {
            return StubWebRtcAudioEngine()
        }
        return RealWebRtcAudioEngine(appContext) { state ->
            onIce.invoke(sessionId, state)
        }
    }
}
