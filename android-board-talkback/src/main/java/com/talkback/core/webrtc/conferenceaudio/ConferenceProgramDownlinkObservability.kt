 package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.webrtc.ProgramSenderSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * P0 observation only: Anchor PROGRAM downlink A/B/C.
 * Does not switch tracks, topology, or recovery.
 */
class ConferenceProgramDownlinkObservability(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val emitMinIntervalMs: Long = 1_000L
) {
    private val inboundFrames = ConcurrentHashMap<String, AtomicLong>()
    private val sentFrames = ConcurrentHashMap<String, AtomicLong>()
    private val lastEmitAt = ConcurrentHashMap<String, AtomicLong>()
    private val programProduced = AtomicLong(0)
    private val routingGeneration = AtomicLong(0)

    fun bumpRoutingGeneration(): Long = routingGeneration.incrementAndGet()

    fun routingGeneration(): Long = routingGeneration.get()

    fun onInboundFrame(
        conferenceId: String,
        anchorId: String,
        remoteSpoke: String,
        pcmFormat: ConferencePcmFormat
    ) {
        val key = "$conferenceId|$remoteSpoke"
        val count = inboundFrames.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        val now = clock()
        if (!shouldEmit("A|$key", now)) return
        ConferenceProgramDownlinkLog.emitInbound(
            conferenceId = conferenceId,
            anchorId = anchorId,
            remoteSpoke = remoteSpoke,
            inboundFrames = count,
            lastInboundTs = now,
            pcmFormat = pcmFormat
        )
    }

    fun onProgramProduced(
        conferenceId: String,
        mixInputCount: Int,
        activeSources: Collection<String>
    ) {
        val produced = programProduced.incrementAndGet()
        val now = clock()
        if (!shouldEmit("B|$conferenceId", now)) return
        ConferenceProgramDownlinkLog.emitMix(
            conferenceId = conferenceId,
            programFramesProduced = produced,
            mixInputCount = mixInputCount,
            activeSources = activeSources,
            routingGeneration = routingGeneration.get(),
            lastProgramTs = now
        )
    }

    fun onProgramSent(
        conferenceId: String,
        spoke: String,
        sender: ProgramSenderSnapshot?
    ) {
        val key = "$conferenceId|$spoke"
        val sent = sentFrames.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        val now = clock()
        if (!shouldEmit("C|$key", now)) return
        ConferenceProgramDownlinkLog.emitSender(
            conferenceId = conferenceId,
            spoke = spoke,
            snapshot = sender,
            programFramesSent = sent,
            lastSendTs = now
        )
    }

    private fun shouldEmit(key: String, now: Long): Boolean {
        val slot = lastEmitAt.getOrPut(key) { AtomicLong(0) }
        while (true) {
            val prev = slot.get()
            if (prev != 0L && now - prev < emitMinIntervalMs) return false
            if (slot.compareAndSet(prev, now)) return true
        }
    }
}
