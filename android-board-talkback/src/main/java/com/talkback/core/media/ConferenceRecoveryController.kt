package com.talkback.core.media

import com.talkback.core.qos.IceConnectivity
import com.talkback.core.webrtc.MediaBearerScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Sole authority that may request PC recreation after failed recovery (ADR-0018).
 * Coordinator supplies signaling via [onIceRestart] / [onAfterRecreate] callbacks only.
 */
class ConferenceRecoveryController(
    private val policy: ConferenceRecoveryPolicy = ConferenceRecoveryPolicy(),
    private val sessionManager: MediaSessionManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onIceRestart: (moduleId: String) -> Boolean = { false },
    private val onAfterRecreate: (moduleId: String) -> Unit = {}
) {
    private data class RecoveryState(
        var iceRestartAttempts: Int = 0,
        var recovering: Boolean = false,
        var checkingSinceMs: Long? = null
    )

    private val recoveryByModule = ConcurrentHashMap<String, RecoveryState>()

    fun policy(): ConferenceRecoveryPolicy = policy

    fun isMediaRecovering(moduleId: String): Boolean =
        recoveryByModule[moduleId]?.recovering == true

    fun notifyIceStateChanged(moduleId: String, iceState: String) {
        val state = recoveryByModule.getOrPut(moduleId) { RecoveryState() }
        when {
            IceConnectivity.isConnected(iceState) -> clearRecovery(moduleId)
            iceState == "CHECKING" -> {
                if (state.checkingSinceMs == null) {
                    state.checkingSinceMs = clock()
                }
            }
            else -> state.checkingSinceMs = null
        }
    }

    fun requestRecovery(channelId: String, moduleId: String, reason: String) {
        val media = sessionManager.getState(moduleId)
        val ice = media?.iceState
        if (ice == null || media.scope != MediaBearerScope.CONFERENCE) {
            requestRecreateIfNeeded(moduleId)
            return
        }

        if (IceConnectivity.isConnected(ice)) {
            clearRecovery(moduleId)
            return
        }

        if (IceConnectivity.isClosed(ice)) {
            requestRecreateIfNeeded(moduleId)
            return
        }

        val state = recoveryByModule.getOrPut(moduleId) { RecoveryState() }
        val checkingTimedOut = ice == "CHECKING" &&
            state.checkingSinceMs?.let { clock() - it >= policy.checkingTimeoutMs } == true
        val shouldEscalate = ice == "FAILED" ||
            ice == "DISCONNECTED" ||
            reason == "CHECKING_TIMEOUT" ||
            checkingTimedOut
        if (!shouldEscalate) return

        state.recovering = true
        if (state.iceRestartAttempts < policy.maxIceRestartAttempts) {
            state.iceRestartAttempts++
            onIceRestart(moduleId)
            return
        }
        requestRecreateIfNeeded(moduleId)
    }

    fun requestRecreateIfNeeded(moduleId: String) {
        val existing = sessionManager.getState(moduleId)
        if (existing != null && !IceConnectivity.isClosed(existing.iceState)) {
            sessionManager.close(moduleId)
        }
        sessionManager.create(moduleId, MediaBearerScope.CONFERENCE)
        clearRecovery(moduleId)
        onAfterRecreate(moduleId)
    }

    fun clearRecovery(moduleId: String) {
        recoveryByModule.remove(moduleId)
    }

    fun clearAll() {
        recoveryByModule.clear()
    }
}
