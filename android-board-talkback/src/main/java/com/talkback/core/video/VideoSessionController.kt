package com.talkback.core.video

import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.model.VideoNegotiationPayload
import com.talkback.core.model.VideoPublishDenyPayload
import com.talkback.core.model.VideoPublishPayload
import com.talkback.core.model.VideoStopPayload

sealed class VideoInboundResult {
    data class AcceptPublish(val canonical: CanonicalVideoPublish) : VideoInboundResult()
    data class DenyPublish(val deny: VideoPublishDenyPayload) : VideoInboundResult()
    data class AcceptNegotiation(val key: VideoNegotiationKey) : VideoInboundResult()
    data class DropStale(val reason: String) : VideoInboundResult()
    data class Stopped(val canonical: CanonicalVideoPublish) : VideoInboundResult()
    object Ignored : VideoInboundResult()
}

/**
 * Conference-scoped video control plane. No camera, no PeerConnection in this step.
 * Negotiation keys are accepted only against the authoritative slot.
 */
class VideoSessionController(
    private val maxViewers: Int = VideoMediaPolicy.DEFAULT_MAX_VIEWERS,
) {
    @Volatile
    var canonical: CanonicalVideoPublish? = null
        private set

    private val negotiations = LinkedHashMap<VideoNegotiationKey, String>()

    fun onInbound(
        signal: SignalEnvelope,
        localIsMembershipAuthority: Boolean,
    ): VideoInboundResult {
        if (!VideoSignalingDemux.isVideoSignal(signal.type)) {
            return VideoInboundResult.Ignored
        }
        return when (signal.type) {
            SignalType.VIDEO_PUBLISH -> onPublish(signal, localIsMembershipAuthority)
            SignalType.VIDEO_PUBLISH_DENY -> onDeny(signal)
            SignalType.VIDEO_STOP -> onStop(signal)
            SignalType.VIDEO_OFFER -> onOffer(signal)
            SignalType.VIDEO_ANSWER, SignalType.VIDEO_ICE -> onNegotiationMedia(signal)
            else -> VideoInboundResult.Ignored
        }
    }

    fun activeViewerCount(): Int = negotiations.size

    fun hasNegotiation(key: VideoNegotiationKey): Boolean = negotiations.containsKey(key)

    fun grantAsAuthority(requesterModuleId: String, streamId: String): VideoPublishDecision {
        val decision = VideoPublishAuthority.decide(canonical, requesterModuleId, streamId)
        when (decision) {
            is VideoPublishDecision.Grant -> canonical = decision.canonical
            is VideoPublishDecision.Refresh -> canonical = decision.canonical
            is VideoPublishDecision.Deny -> Unit
        }
        return decision
    }

    fun noteNegotiation(key: VideoNegotiationKey, viewerModuleId: String): Boolean {
        val slot = canonical ?: return false
        if (key.streamId != slot.streamId || key.videoGeneration != slot.videoGeneration) return false
        if (negotiations.size >= maxViewers && !negotiations.containsKey(key)) return false
        negotiations[key] = viewerModuleId
        return true
    }

    fun clearSlot() {
        canonical = null
        negotiations.clear()
    }

    private fun onPublish(
        signal: SignalEnvelope,
        localIsMembershipAuthority: Boolean,
    ): VideoInboundResult {
        val payload = VideoPublishPayload.decode(signal.payload) ?: return VideoInboundResult.DropStale("BAD_PUBLISH")
        if (!localIsMembershipAuthority) {
            if (!payload.authoritative) return VideoInboundResult.Ignored
            if (payload.videoGeneration <= 0L) return VideoInboundResult.DropStale("BAD_GENERATION")
            canonical = CanonicalVideoPublish(
                publisherModuleId = payload.publisherModuleId,
                streamId = payload.streamId,
                videoGeneration = payload.videoGeneration,
            )
            dropOtherGenerations()
            return VideoInboundResult.AcceptPublish(canonical!!)
        }
        when (val decision = VideoPublishAuthority.decide(canonical, payload.publisherModuleId, payload.streamId)) {
            is VideoPublishDecision.Grant -> {
                canonical = decision.canonical
                return VideoInboundResult.AcceptPublish(decision.canonical)
            }
            is VideoPublishDecision.Refresh -> {
                canonical = decision.canonical
                return VideoInboundResult.AcceptPublish(decision.canonical)
            }
            is VideoPublishDecision.Deny -> {
                return VideoInboundResult.DenyPublish(
                    VideoPublishDenyPayload(
                        streamId = decision.current.streamId,
                        videoGeneration = decision.current.videoGeneration,
                        currentPublisherModuleId = decision.current.publisherModuleId,
                        requesterModuleId = payload.publisherModuleId,
                    )
                )
            }
        }
    }

    private fun onDeny(signal: SignalEnvelope): VideoInboundResult {
        val payload = VideoPublishDenyPayload.decode(signal.payload)
            ?: return VideoInboundResult.DropStale("BAD_DENY")
        val slot = canonical
        if (slot != null &&
            slot.publisherModuleId == payload.requesterModuleId &&
            slot.videoGeneration != payload.videoGeneration
        ) {
            canonical = null
            negotiations.clear()
        }
        return VideoInboundResult.DropStale("DENIED")
    }

    private fun onStop(signal: SignalEnvelope): VideoInboundResult {
        val payload = VideoStopPayload.decode(signal.payload) ?: return VideoInboundResult.DropStale("BAD_STOP")
        val slot = canonical ?: return VideoInboundResult.Ignored
        if (slot.streamId != payload.streamId || slot.videoGeneration != payload.videoGeneration) {
            return VideoInboundResult.DropStale("STALE_STOP")
        }
        if (slot.publisherModuleId != payload.publisherModuleId) {
            return VideoInboundResult.DropStale("STOP_NOT_OWNER")
        }
        canonical = null
        negotiations.clear()
        return VideoInboundResult.Stopped(slot)
    }

    private fun onOffer(signal: SignalEnvelope): VideoInboundResult {
        val payload = VideoNegotiationPayload.decode(signal.payload)
            ?: return VideoInboundResult.DropStale("BAD_OFFER")
        val slot = canonical ?: return VideoInboundResult.DropStale("NO_PUBLISH")
        if (payload.streamId != slot.streamId || payload.videoGeneration != slot.videoGeneration) {
            return VideoInboundResult.DropStale("STALE_GENERATION")
        }
        if (payload.publisherModuleId != slot.publisherModuleId) {
            return VideoInboundResult.DropStale("WRONG_PUBLISHER")
        }
        if (negotiations.size >= maxViewers &&
            negotiations.keys.none { it.negotiationId == payload.negotiationId }
        ) {
            return VideoInboundResult.DropStale("MAX_VIEWERS")
        }
        val key = VideoNegotiationKey(
            sessionId = signal.sessionId,
            streamId = payload.streamId,
            videoGeneration = payload.videoGeneration,
            negotiationId = payload.negotiationId,
        )
        negotiations[key] = payload.viewerModuleId
        return VideoInboundResult.AcceptNegotiation(key)
    }

    private fun onNegotiationMedia(signal: SignalEnvelope): VideoInboundResult {
        val payload = VideoNegotiationPayload.decode(signal.payload)
            ?: return VideoInboundResult.DropStale("BAD_NEGOTIATION")
        val key = VideoNegotiationKey(
            sessionId = signal.sessionId,
            streamId = payload.streamId,
            videoGeneration = payload.videoGeneration,
            negotiationId = payload.negotiationId,
        )
        val slot = canonical
        if (slot == null ||
            slot.streamId != payload.streamId ||
            slot.videoGeneration != payload.videoGeneration
        ) {
            return VideoInboundResult.DropStale("STALE_GENERATION")
        }
        if (!negotiations.containsKey(key)) {
            return VideoInboundResult.DropStale("UNKNOWN_NEGOTIATION")
        }
        return VideoInboundResult.AcceptNegotiation(key)
    }

    private fun dropOtherGenerations() {
        val slot = canonical ?: return
        negotiations.keys.removeAll { it.streamId != slot.streamId || it.videoGeneration != slot.videoGeneration }
    }
}
