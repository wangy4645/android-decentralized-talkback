package com.talkback.core.webrtc

import com.talkback.core.util.TalkbackLog

/**
 * P0.1c: behavior-neutral leak detector.
 * Conference control facts must not execute PeerConnection JNI on the coordinator thread.
 */
object WebRtcJniThreadGuard {
    const val COORDINATOR_THREAD_NAME = "talkback-coordinator"

    fun warnIfCoordinator(op: String) {
        val thread = Thread.currentThread()
        if (thread.name != COORDINATOR_THREAD_NAME) return
        TalkbackLog.w(
            "MEDIA_JNI_ON_COORDINATOR op=$op thread=${thread.name} tid=${thread.id}"
        )
    }
}
