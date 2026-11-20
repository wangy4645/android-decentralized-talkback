package com.talkback.core.video

/** Canonical live-video slot owned by conference membership authority. */
data class CanonicalVideoPublish(
    val publisherModuleId: String,
    val streamId: String,
    val videoGeneration: Long,
)

sealed class VideoPublishDecision {
    data class Grant(val canonical: CanonicalVideoPublish) : VideoPublishDecision()
    data class Refresh(val canonical: CanonicalVideoPublish) : VideoPublishDecision()
    data class Deny(val current: CanonicalVideoPublish) : VideoPublishDecision()
}

/**
 * Single-publisher arbitration. Same conference membership authority as roster.
 * Same-tick ties: lexicographically smaller moduleId wins.
 */
object VideoPublishAuthority {

    fun winnerModuleId(moduleIds: Collection<String>): String? =
        moduleIds.filter { it.isNotBlank() }.minOrNull()

    fun decide(
        current: CanonicalVideoPublish?,
        requesterModuleId: String,
        requestedStreamId: String,
    ): VideoPublishDecision {
        require(requesterModuleId.isNotBlank())
        require(requestedStreamId.isNotBlank())
        if (current == null) {
            return VideoPublishDecision.Grant(
                CanonicalVideoPublish(
                    publisherModuleId = requesterModuleId,
                    streamId = requestedStreamId,
                    videoGeneration = 1L,
                )
            )
        }
        if (current.publisherModuleId == requesterModuleId) {
            return VideoPublishDecision.Refresh(current)
        }
        return VideoPublishDecision.Deny(current)
    }

    /**
     * Empty slot, multiple simultaneous requesters: grant the lexicographic winner
     * with generation 1; callers deny the rest against that canonical.
     */
    fun decideSameTick(
        requesterModuleIds: Collection<String>,
        streamIdByModule: Map<String, String>,
    ): Pair<VideoPublishDecision.Grant, List<String>>? {
        val winner = winnerModuleId(requesterModuleIds) ?: return null
        val streamId = streamIdByModule[winner] ?: return null
        val grant = VideoPublishDecision.Grant(
            CanonicalVideoPublish(
                publisherModuleId = winner,
                streamId = streamId,
                videoGeneration = 1L,
            )
        )
        val losers = requesterModuleIds.filter { it != winner }
        return grant to losers
    }
}
