package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.transport.AbsoluteMediaPlayoutClock
import com.talkback.core.conference.session.integration.cutover.AudibleOwnershipState
import com.talkback.core.conference.session.integration.cutover.ReplacementCutoverRc1
import com.talkback.core.conference.transport.PipelinePlayoutResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * PR-PA-SR4-PLAY — session-scoped absolute 20 ms playout clock →
 * [ConferenceSessionMediaWiring.runMixPlayoutCycle] (existing Top-K / decode / mix / metrics seam).
 *
 * Does not own AudioTrack or Profile03 parameters. Tick failures are OBS-only.
 */
class Profile01ShadowPlayoutClockSeam(
    private val wiringProvider: () -> ConferenceSessionMediaWiring? = { ConferenceSessionMediaBridge.wiring },
    private val hasSession: (String) -> Boolean = { ConferenceSessionMediaBridge.hasSession(it) },
    private val sessionAnchorMs: (String) -> Long? = { id ->
        wiringProvider()?.sessionPlayoutAnchorMs(id)
    },
    private val resolveBufferedSlot: (
        ConferenceSessionMediaWiring,
        String,
        Long,
    ) -> ConferenceSessionMediaWiring.BufferedMixSlot? = { wiring, sessionId, tickMediaTimeMs ->
        wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs)
    },
    private val runMixPlayoutCycle: (
        ConferenceSessionMediaWiring,
        String,
        Long,
        Long,
        Long,
    ) -> PipelinePlayoutResult? = { wiring, sessionId, nowMs, slot, slotMediaTimeMs ->
        wiring.runMixPlayoutCycle(sessionId, nowMs, slot, slotMediaTimeMs)
    },
    private val clockFactory: (
        anchorMs: Long,
        threadName: String,
        onTick: (Long) -> Unit,
    ) -> AbsoluteMediaPlayoutClock = { anchorMs, threadName, onTick ->
        AbsoluteMediaPlayoutClock(
            anchorMs = anchorMs,
            threadName = threadName,
            onTick = onTick,
        )
    },
    private val disarmJoinTimeoutMs: Long = DEFAULT_DISARM_JOIN_TIMEOUT_MS,
) {
    private data class ArmedSession(
        val sessionId: String,
        val anchorMs: Long,
        val clock: AbsoluteMediaPlayoutClock,
        val mixCyclesExecuted: AtomicLong,
        val tickFailures: AtomicLong,
    )

    private val armed = ConcurrentHashMap<String, ArmedSession>()

    fun onShadowSessionStarted(sessionId: String) {
        if (!MeetingProductMediaShadow.enabled) return
        try {
            if (!hasSession(sessionId)) return
            if (armed.containsKey(sessionId)) {
                Profile01ShadowRuntimeObservability.logShadowPlayoutArmed(
                    sessionId = sessionId,
                    outcome = "ALREADY_ARMED",
                    anchorMs = armed[sessionId]?.anchorMs,
                )
                return
            }
            val anchorMs = sessionAnchorMs(sessionId)
            if (anchorMs == null) {
                Profile01ShadowRuntimeObservability.logShadowPlayoutFailed(
                    sessionId = sessionId,
                    reason = "NO_SESSION_ANCHOR",
                    detail = "missing startedAtMs",
                )
                return
            }
            val mixCyclesExecuted = AtomicLong(0L)
            val tickFailures = AtomicLong(0L)
            lateinit var armedSession: ArmedSession
            val clock =
                clockFactory(
                    anchorMs,
                    "shadow-playout-$sessionId",
                ) { tickMediaTimeMs ->
                    onPlayoutTick(armedSession, tickMediaTimeMs)
                }
            armedSession =
                ArmedSession(
                    sessionId = sessionId,
                    anchorMs = anchorMs,
                    clock = clock,
                    mixCyclesExecuted = mixCyclesExecuted,
                    tickFailures = tickFailures,
                )
            val prior = armed.putIfAbsent(sessionId, armedSession)
            if (prior != null) {
                clock.stop()
                Profile01ShadowRuntimeObservability.logShadowPlayoutArmed(
                    sessionId = sessionId,
                    outcome = "ALREADY_ARMED",
                    anchorMs = prior.anchorMs,
                )
                return
            }
            clock.start()
            Profile01ShadowRuntimeObservability.logShadowPlayoutArmed(
                sessionId = sessionId,
                outcome = "APPLIED",
                anchorMs = anchorMs,
            )
        } catch (t: Throwable) {
            armed.remove(sessionId)
            Profile01ShadowRuntimeObservability.logShadowPlayoutFailed(
                sessionId = sessionId,
                reason = t.javaClass.simpleName,
                detail = t.message,
            )
        }
    }

    /**
     * Disarm playout clock and wait for tick thread termination before runtime teardown (PLAY-HC1).
     */
    fun onShadowSessionStopping(sessionId: String) {
        disarmSession(sessionId)
    }

    fun onSessionStopped(sessionId: String) {
        disarmSession(sessionId)
    }

    internal fun isArmed(sessionId: String): Boolean = armed.containsKey(sessionId)

    internal fun mixCyclesExecuted(sessionId: String): Long = armed[sessionId]?.mixCyclesExecuted?.get() ?: 0L

    private fun disarmSession(sessionId: String) {
        val session = armed.remove(sessionId) ?: return
        session.clock.stop()
        Profile01ShadowRuntimeObservability.logShadowPlayoutDisarmed(
            sessionId = sessionId,
            mixCyclesExecuted = session.mixCyclesExecuted.get(),
            tickFailures = session.tickFailures.get(),
        )
    }

    private fun onPlayoutTick(
        session: ArmedSession,
        tickMediaTimeMs: Long,
    ) {
        if (!armed.containsKey(session.sessionId)) return
        if (!hasSession(session.sessionId)) return
        val wiring = wiringProvider() ?: return
        if (wiring.isIngressBlocked(session.sessionId)) return
        try {
            wiring.withSessionPipelineLock(session.sessionId) {
                if (!hasSession(session.sessionId)) return@withSessionPipelineLock
                val buffered =
                    resolveBufferedSlot(wiring, session.sessionId, tickMediaTimeMs)
                        ?: run {
                            wiring.capturePlayoutFunnelSnapshot(
                                sessionId = session.sessionId,
                                tickMediaTimeMs = tickMediaTimeMs,
                            )?.let { funnel ->
                                Profile01ShadowRuntimeObservability.maybeLogPlayoutBufferStarvation(
                                    sessionId = session.sessionId,
                                    funnel = funnel,
                                    liveDecoders = wiring.runtimeSnapshot(session.sessionId)?.liveDecoders ?: 0,
                                )
                            }
                            return@withSessionPipelineLock
                        }
                val nowMs = System.currentTimeMillis()
                val result =
                    runMixPlayoutCycle(
                        wiring,
                        session.sessionId,
                        nowMs,
                        buffered.slot,
                        buffered.slotMediaTimeMs,
                    )
                if (result != null) {
                    session.mixCyclesExecuted.incrementAndGet()
                    val liveDecoders = wiring.runtimeSnapshot(session.sessionId)?.liveDecoders ?: 0
                    val successfulPlayoutWrites =
                        wiring.playoutSuccessfulWrites(session.sessionId) ?: 0L
                    val audioTrackOwner = rc1AudioTrackOwnerLabel()
                    wiring.capturePlayoutFunnelSnapshot(
                        sessionId = session.sessionId,
                        tickMediaTimeMs = tickMediaTimeMs,
                        resolvedMixSlot = buffered.slot,
                    )?.let { funnel ->
                        Profile01ShadowRuntimeObservability.maybeLogPlayoutFunnelCycle(
                            sessionId = session.sessionId,
                            funnel = funnel,
                            liveDecoders = liveDecoders,
                            successfulPlayoutWrites = successfulPlayoutWrites,
                            audioTrackOwner = audioTrackOwner,
                        )
                    }
                    Profile01ShadowRuntimeObservability.maybeLogShadowPlayoutCycle(
                        sessionId = session.sessionId,
                        tickMediaTimeMs = tickMediaTimeMs,
                        slot = buffered.slot,
                        liveDecoders = liveDecoders,
                        successfulPlayoutWrites = successfulPlayoutWrites,
                        audioTrackOwner = audioTrackOwner,
                    )
                }
            }
        } catch (t: Throwable) {
            session.tickFailures.incrementAndGet()
            Profile01ShadowRuntimeObservability.logShadowPlayoutTickFailed(
                sessionId = session.sessionId,
                reason = t.javaClass.simpleName,
                detail = t.message,
                stackTrace = t.stackTraceToString(),
            )
        }
    }

    private fun rc1AudioTrackOwnerLabel(): String =
        when (ReplacementCutoverRc1.currentState()) {
            AudibleOwnershipState.MULTICAST_ACTIVE -> "MULTICAST_PRODUCTION"
            AudibleOwnershipState.CUTOVER_ARMED -> "CUTOVER_ARMED"
            AudibleOwnershipState.ANCHOR_ACTIVE, null -> "SHADOW_METRICS_ONLY"
        }

    companion object {
        const val DEFAULT_DISARM_JOIN_TIMEOUT_MS = 2_000L
    }
}
