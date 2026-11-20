package com.talkback.core.webrtc

import android.content.Context
import com.talkback.core.util.TalkbackLog
import com.talkback.core.video.VideoMediaPolicy
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * One camera / VideoSource shared by all publisher video PeerConnections.
 */
internal object SharedLocalVideo {
    private val lock = Any()
    private var eglBase: EglBase? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private var attachedPcCount = 0
    private var keepAlive = false

    fun acquireLocalTrack(context: Context, factory: PeerConnectionFactory): VideoTrack? {
        synchronized(lock) {
            if (localTrack != null) return localTrack
            return runCatching { startLocked(context.applicationContext, factory) }
                .onFailure { TalkbackLog.w("VIDEO_CAPTURE start_failed ${it.message}") }
                .getOrNull()
        }
    }

    fun notePeerAttached() {
        synchronized(lock) { attachedPcCount++ }
    }

    fun notePeerDetached() {
        synchronized(lock) {
            if (attachedPcCount > 0) attachedPcCount--
            if (attachedPcCount == 0 && !keepAlive) stopLocked()
        }
    }

    fun setKeepAlive(keep: Boolean) {
        synchronized(lock) {
            keepAlive = keep
            if (!keep && attachedPcCount == 0) stopLocked()
        }
    }

    fun eglBaseContext(): EglBase.Context? = synchronized(lock) { eglBase?.eglBaseContext }

    fun addPreviewSink(sink: VideoSink) {
        synchronized(lock) { localTrack?.addSink(sink) }
    }

    fun removePreviewSink(sink: VideoSink) {
        synchronized(lock) { runCatching { localTrack?.removeSink(sink) } }
    }

    fun setSendEnabled(enabled: Boolean) {
        synchronized(lock) { localTrack?.setEnabled(enabled) }
    }

    private fun startLocked(context: Context, factory: PeerConnectionFactory): VideoTrack {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        require(names.isNotEmpty()) { "no camera" }
        val device = names.firstOrNull { enumerator.isBackFacing(it) } ?: names.first()
        val cap = enumerator.createCapturer(device, null)
        val egl = EglBase.create()
        val surfaceHelper = SurfaceTextureHelper.create("tb-video-capture", egl.eglBaseContext)
        val source = factory.createVideoSource(false)
        cap.initialize(surfaceHelper, context, source.capturerObserver)
        cap.startCapture(
            VideoMediaPolicy.DEFAULT_WIDTH,
            VideoMediaPolicy.DEFAULT_HEIGHT,
            VideoMediaPolicy.DEFAULT_FPS,
        )
        val track = factory.createVideoTrack("tb_shared_video", source)
        track.setEnabled(true)
        eglBase = egl
        helper = surfaceHelper
        capturer = cap
        videoSource = source
        localTrack = track
        TalkbackLog.i("VIDEO_CAPTURE started device=$device")
        return track
    }

    private fun stopLocked() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { localTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { eglBase?.release() }
        capturer = null
        localTrack = null
        videoSource = null
        helper = null
        eglBase = null
        TalkbackLog.i("VIDEO_CAPTURE stopped")
    }
}
