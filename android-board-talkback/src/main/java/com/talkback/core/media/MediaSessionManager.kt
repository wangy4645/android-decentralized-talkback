package com.talkback.core.media

import com.talkback.core.qos.IceConnectivity
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.ModuleMediaEngineFactory
import com.talkback.core.webrtc.WebRtcAudioEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * RO-M2a: session-bound mesh media lifecycle. One PeerConnection generation per scope transition.
 * GROUP → MEETING must not reuse a live GROUP PeerConnection (ADR-0017 / KPI MEDIA_SESSION_REUSE=0).
 * CONFERENCE → CONFERENCE reuses a live PC for CONNECTED/CHECKING/DISCONNECTED/FAILED (ADR-0018).
 */
class MediaSessionManager(
    private val factory: ModuleMediaEngineFactory,
    private val closedWaitTimeoutMs: Long = 3_000L,
    private val pollIntervalMs: Long = 10L,
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private data class Entry(
        val moduleId: String,
        val engine: WebRtcAudioEngine,
        val scope: MediaBearerScope,
        var lifecycle: MediaLifecycle,
        var iceState: String,
        val generation: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val generationByModule = ConcurrentHashMap<String, Long>()
    private val pendingClosed = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var reuseViolations = 0

    fun create(moduleId: String, scope: MediaBearerScope): WebRtcAudioEngine {
        val existing = entries[moduleId]
        if (existing != null && shouldReuseConferenceSession(existing, scope)) {
            return existing.engine
        }
        if (existing != null && !IceConnectivity.isClosed(existing.iceState)) {
            reuseViolations++
            MediaObservabilityLog.mediaSessionReuse(
                moduleId = moduleId,
                previousScope = existing.scope,
                requestedScope = scope,
                generation = existing.generation
            )
            close(moduleId)
            awaitClosed(setOf(moduleId))
        }
        factory.release(moduleId)
        val generation = (generationByModule[moduleId] ?: 0L) + 1L
        generationByModule[moduleId] = generation
        val engine = factory.getOrCreate(moduleId)
        val entry = Entry(
            moduleId = moduleId,
            engine = engine,
            scope = scope,
            lifecycle = MediaLifecycle.BOOTSTRAPPING,
            iceState = engine.iceConnectionState(),
            generation = generation
        )
        entries[moduleId] = entry
        logLifecycle(entry)
        return engine
    }

    fun close(moduleId: String) {
        val entry = entries.remove(moduleId)
        pendingClosed.add(moduleId)
        factory.release(moduleId)
        if (entry == null || IceConnectivity.isClosed(entry.engine.iceConnectionState())) {
            pendingClosed.remove(moduleId)
        }
        entry?.let {
            logLifecycle(it.copy(iceState = "CLOSED", lifecycle = MediaLifecycle.IDLE))
        }
    }

    fun resetAll(moduleIds: Collection<String>, channelId: String? = null): MediaBarrierResult {
        val targets = moduleIds.toSet()
        targets.forEach { close(it) }
        val unresolved = awaitClosed(targets)
        MediaObservabilityLog.mediaBarrierComplete(
            moduleCount = targets.size,
            unresolvedCount = unresolved.size,
            channelId = channelId
        )
        return MediaBarrierResult(targets.size, unresolved)
    }

    fun getState(moduleId: String): MediaSessionState? {
        val entry = entries[moduleId] ?: return null
        return MediaSessionState(
            moduleId = moduleId,
            scope = entry.scope,
            lifecycle = entry.lifecycle,
            iceState = entry.iceState,
            generation = entry.generation
        )
    }

    fun getEngine(moduleId: String): WebRtcAudioEngine? = entries[moduleId]?.engine

    fun onIceStateChanged(moduleId: String, iceState: String) {
        val entry = entries[moduleId] ?: run {
            if (IceConnectivity.isClosed(iceState)) {
                pendingClosed.remove(moduleId)
            }
            return
        }
        entry.iceState = iceState
        entry.lifecycle = lifecycleFromIce(iceState, entry.lifecycle)
        if (IceConnectivity.isClosed(iceState)) {
            pendingClosed.remove(moduleId)
        }
        logLifecycle(entry)
    }

    fun mediaSessionReuseCount(): Int = reuseViolations

    fun activeModuleIds(): Set<String> = entries.keys.toSet()

    fun closeAll() {
        entries.keys.toList().forEach { close(it) }
        factory.releaseAll()
    }

    internal fun pendingClosedModules(): Set<String> = pendingClosed.toSet()

    private fun awaitClosed(moduleIds: Set<String>): List<String> {
        if (moduleIds.isEmpty()) return emptyList()
        val deadline = clock() + closedWaitTimeoutMs
        while (clock() < deadline) {
            val unresolved = moduleIds.filter { pendingClosed.contains(it) }
            if (unresolved.isEmpty()) return emptyList()
            sleeper(pollIntervalMs)
        }
        return moduleIds.filter { pendingClosed.contains(it) }
    }

    private fun lifecycleFromIce(iceState: String, current: MediaLifecycle): MediaLifecycle = when {
        IceConnectivity.isClosed(iceState) -> MediaLifecycle.IDLE
        iceState == "FAILED" -> MediaLifecycle.FAILED
        iceState == "DISCONNECTED" -> MediaLifecycle.DEGRADED
        IceConnectivity.isConnected(iceState) -> MediaLifecycle.CONNECTED
        iceState == "CHECKING" -> MediaLifecycle.NEGOTIATING
        iceState == "NEW" -> when (current) {
            MediaLifecycle.IDLE -> MediaLifecycle.BOOTSTRAPPING
            else -> current
        }
        else -> current
    }

    private fun shouldReuseConferenceSession(existing: Entry, requestedScope: MediaBearerScope): Boolean =
        requestedScope == MediaBearerScope.CONFERENCE &&
            existing.scope == MediaBearerScope.CONFERENCE &&
            !IceConnectivity.isClosed(existing.iceState)

    private fun logLifecycle(entry: Entry) {
        MediaObservabilityLog.mediaLifecycle(
            moduleId = entry.moduleId,
            scope = entry.scope,
            lifecycle = entry.lifecycle,
            generation = entry.generation,
            iceState = entry.iceState
        )
    }
}
