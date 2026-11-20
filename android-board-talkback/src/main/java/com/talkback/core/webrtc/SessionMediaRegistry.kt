package com.talkback.core.webrtc

import android.content.Context
import com.talkback.core.media.CreateContinuationEligibility
import com.talkback.core.media.EngineRequestIntent
import com.talkback.core.media.MediaBarrierResult
import com.talkback.core.media.MediaSessionManager
import com.talkback.core.media.MediaSessionState
import com.talkback.core.media.MeshMediaAsyncRelease
import com.talkback.core.media.MeshMediaCoordinatorDeferral
import com.talkback.core.video.VideoNegotiationKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Mesh (group/conference) reuses [ModuleMediaEngineFactory] via [MediaSessionManager];
 * unicast uses a separate PC per call.
 */
class SessionMediaRegistry(
    context: Context,
    private val useStub: Boolean = false,
    onMeshIce: (MediaBearerScope, String, String) -> Unit,
    private val onUnicastIce: (String, String) -> Unit,
    private val onVideoIce: ((String, String) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    val sessionManager: MediaSessionManager
    private val unicastEngines = ConcurrentHashMap<String, WebRtcAudioEngine>()
    private val videoEngines = ConcurrentHashMap<String, Pair<VideoNegotiationKey, WebRtcAudioEngine>>()
    @Volatile
    private var localVideoPreviewFactoryHeld = false

    init {
        lateinit var manager: MediaSessionManager
        val factory = ModuleMediaEngineFactory(appContext, useStub) { moduleId, state ->
            manager.onIceStateChanged(moduleId, state)
        }
        manager = MediaSessionManager(
            factory = factory,
            onScopedMeshIce = onMeshIce
        )
        sessionManager = manager
    }

    fun installAsyncMeshMediaRelease(release: MeshMediaAsyncRelease) {
        sessionManager.installAsyncMeshMediaRelease(release)
    }

    fun installMeshMediaCoordinatorDeferral(deferral: MeshMediaCoordinatorDeferral) {
        sessionManager.installMeshMediaCoordinatorDeferral(deferral)
    }

    fun installCreateContinuationEligibility(eligibility: CreateContinuationEligibility) {
        sessionManager.installCreateContinuationEligibility(eligibility)
    }

    fun registerHangupMediaBarrier(
        moduleIds: Collection<String>,
        onComplete: (allSucceeded: Boolean) -> Unit
    ) {
        sessionManager.registerHangupMediaBarrier(moduleIds, onComplete)
    }

    fun groupEngine(remoteModuleId: String): WebRtcAudioEngine =
        sessionManager.create(remoteModuleId, MediaBearerScope.GROUP)

    fun requestGroupEngine(
        remoteModuleId: String,
        sessionId: String?,
        onReady: (WebRtcAudioEngine) -> Unit,
        onFailed: (() -> Unit)? = null
    ) {
        sessionManager.requestEngine(remoteModuleId, MediaBearerScope.GROUP, sessionId, onReady, onFailed)
    }

    fun getGroup(remoteModuleId: String): WebRtcAudioEngine? =
        sessionManager.getEngine(remoteModuleId)?.takeIf {
            sessionManager.getState(remoteModuleId)?.scope == MediaBearerScope.GROUP
        }

    fun releaseGroup(remoteModuleId: String, sessionId: String? = null) {
        sessionManager.close(remoteModuleId, sessionId)
    }

    /**
     * A0.4: admit conference release on coordinator without waiting for teardown JNI queue.
     * Schedules [MEDIA_RELEASE_SCHEDULED] immediately; native release still runs on edge executor.
     */
    fun admitConferenceRelease(remoteModuleId: String, sessionId: String?, origin: String) {
        sessionManager.admitConferenceRelease(remoteModuleId, sessionId, origin)
    }

    fun getMesh(remoteModuleId: String): WebRtcAudioEngine? =
        sessionManager.getEngine(remoteModuleId)

    fun abortPendingNegotiation() {
        sessionManager.abortPendingNegotiation()
        unicastEngines.values.forEach { it.abortPendingNegotiation() }
    }

    fun conferenceEngine(remoteModuleId: String): WebRtcAudioEngine =
        sessionManager.create(remoteModuleId, MediaBearerScope.CONFERENCE)

    fun requestConferenceEngine(
        remoteModuleId: String,
        sessionId: String?,
        onReady: (WebRtcAudioEngine) -> Unit,
        onFailed: (() -> Unit)? = null,
        intent: EngineRequestIntent = EngineRequestIntent.DEFAULT,
    ) {
        sessionManager.requestEngine(
            remoteModuleId,
            MediaBearerScope.CONFERENCE,
            sessionId,
            onReady,
            onFailed,
            intent,
        )
    }

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

    fun startLocalVideoKeepAlive() {
        if (useStub) return
        SharedLocalVideo.setKeepAlive(true)
        if (localVideoPreviewFactoryHeld) return
        val factory = WebRtcSharedFactory.acquire(appContext)
        val track = SharedLocalVideo.acquireLocalTrack(appContext, factory)
        if (track == null) {
            WebRtcSharedFactory.release("video-preview")
            SharedLocalVideo.setKeepAlive(false)
            return
        }
        localVideoPreviewFactoryHeld = true
    }

    fun stopLocalVideoKeepAlive() {
        SharedLocalVideo.setKeepAlive(false)
        if (localVideoPreviewFactoryHeld) {
            WebRtcSharedFactory.release("video-preview")
            localVideoPreviewFactoryHeld = false
        }
    }

    fun attachLocalVideoPreview(sink: org.webrtc.VideoSink) {
        SharedLocalVideo.addPreviewSink(sink)
    }

    fun detachLocalVideoPreview(sink: org.webrtc.VideoSink) {
        SharedLocalVideo.removePreviewSink(sink)
    }

    fun obtainVideoEngine(key: VideoNegotiationKey, role: VideoPcRole): WebRtcAudioEngine {
        val existing = videoEngines[key.negotiationId]
        if (existing != null) return existing.second
        val engine = if (useStub) {
            StubWebRtcAudioEngine(observedModuleId = "v:${key.negotiationId}")
        } else {
            RealWebRtcVideoEngine(
                context = appContext,
                observedId = "v:${key.negotiationId}",
                role = role,
            ) { state ->
                onVideoIce?.invoke(key.negotiationId, state)
            }
        }
        videoEngines[key.negotiationId] = key to engine
        return engine
    }

    fun getVideoEngine(key: VideoNegotiationKey): WebRtcAudioEngine? =
        videoEngines[key.negotiationId]?.second

    fun releaseVideoEngine(key: VideoNegotiationKey) {
        videoEngines.remove(key.negotiationId)?.second?.release()
    }

    fun forEachVideoEngine(block: (WebRtcAudioEngine) -> Unit) {
        videoEngines.values.forEach { block(it.second) }
    }

    fun releaseVideoForSession(sessionId: String) {
        val stale = videoEngines.filterValues { it.first.sessionId == sessionId }.keys
        stale.forEach { id ->
            videoEngines.remove(id)?.second?.release()
        }
    }

    fun releaseAll() {
        sessionManager.closeAll()
        unicastEngines.values.forEach { runCatching { it.release() } }
        unicastEngines.clear()
        videoEngines.values.forEach { runCatching { it.second.release() } }
        videoEngines.clear()
        stopLocalVideoKeepAlive()
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
