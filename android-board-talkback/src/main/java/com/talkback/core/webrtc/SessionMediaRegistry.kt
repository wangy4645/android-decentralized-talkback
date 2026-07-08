package com.talkback.core.webrtc

import android.content.Context
import com.talkback.core.media.MediaBarrierResult
import com.talkback.core.media.MediaSessionManager
import com.talkback.core.media.MediaSessionState
import java.util.concurrent.ConcurrentHashMap

/**
 * Mesh (group/conference) reuses [ModuleMediaEngineFactory] via [MediaSessionManager];
 * unicast uses a separate PC per call.
 */
class SessionMediaRegistry(
    context: Context,
    private val useStub: Boolean = false,
    onMeshIce: (String, String) -> Unit,
    private val onUnicastIce: (String, String) -> Unit
) {
    private val appContext = context.applicationContext
    val sessionManager: MediaSessionManager
    private val unicastEngines = ConcurrentHashMap<String, WebRtcAudioEngine>()

    init {
        lateinit var manager: MediaSessionManager
        val factory = ModuleMediaEngineFactory(appContext, useStub) { moduleId, state ->
            manager.onIceStateChanged(moduleId, state)
            onMeshIce(moduleId, state)
        }
        manager = MediaSessionManager(factory)
        sessionManager = manager
    }

    fun groupEngine(remoteModuleId: String): WebRtcAudioEngine =
        sessionManager.create(remoteModuleId, MediaBearerScope.GROUP)

    fun getGroup(remoteModuleId: String): WebRtcAudioEngine? =
        sessionManager.getEngine(remoteModuleId)?.takeIf {
            sessionManager.getState(remoteModuleId)?.scope == MediaBearerScope.GROUP
        }

    fun releaseGroup(remoteModuleId: String) {
        sessionManager.close(remoteModuleId)
    }

    fun getMesh(remoteModuleId: String): WebRtcAudioEngine? =
        sessionManager.getEngine(remoteModuleId)

    fun conferenceEngine(remoteModuleId: String): WebRtcAudioEngine =
        sessionManager.create(remoteModuleId, MediaBearerScope.CONFERENCE)

    fun getConference(remoteModuleId: String): WebRtcAudioEngine? =
        sessionManager.getEngine(remoteModuleId)?.takeIf {
            sessionManager.getState(remoteModuleId)?.scope == MediaBearerScope.CONFERENCE
        }

    fun meshSessionState(remoteModuleId: String): MediaSessionState? =
        sessionManager.getState(remoteModuleId)

    fun resetMeshForMeetingBarrier(
        moduleIds: Collection<String>,
        channelId: String? = null
    ): MediaBarrierResult = sessionManager.resetAll(moduleIds, channelId)

    fun mediaSessionReuseCount(): Int = sessionManager.mediaSessionReuseCount()

    fun unicastEngine(sessionId: String): WebRtcAudioEngine =
        unicastEngines.getOrPut(sessionId) { createUnicastEngine(sessionId, onUnicastIce) }

    fun getUnicast(sessionId: String): WebRtcAudioEngine? = unicastEngines[sessionId]

    fun releaseUnicast(sessionId: String) {
        unicastEngines.remove(sessionId)?.release()
    }

    fun releaseAll() {
        sessionManager.closeAll()
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
