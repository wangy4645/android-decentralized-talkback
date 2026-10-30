package com.talkback.core.session

/**
 * P0.1g-1: behavior-neutral SRD admission-to-execution trace (coordinator → executor).
 */
object ConferenceSrdObservability {

    data class Context(
        val sessionId: String,
        val remoteModuleId: String,
        val localModuleId: String,
        val pcGeneration: Long,
        val conferenceGeneration: Long? = null,
        val edgeKey: String = ConferenceMediaJniAffinity.edgeKey(sessionId, remoteModuleId),
        val executorName: String = "tb-edge-$edgeKey",
        val offerLineageId: String? = null,
        val realizationAttemptId: String? = null,
        val hostLocalOfferLineageId: String? = null,
        val lineageCorrelation: String? = null
    ) {
        val edgeLabel: String get() = "$localModuleId->$remoteModuleId"

        fun lineageFields(): String = buildString {
            append(" offerLineageId=")
            append(offerLineageId?.takeIf { it.isNotBlank() } ?: ConferenceRealizationLineage.UNKNOWN)
            append(" realizationAttemptId=")
            append(realizationAttemptId?.takeIf { it.isNotBlank() } ?: ConferenceRealizationLineage.UNKNOWN)
            hostLocalOfferLineageId?.let { append(" hostLocalOfferLineageId=").append(it) }
            lineageCorrelation?.let { append(" lineageCorrelation=").append(it) }
        }
    }

    fun formatDispatch(ctx: Context): String = formatEvent("SRD_DISPATCH", ctx)

    fun formatExecutorEnter(ctx: Context, queueWaitMs: Long): String =
        buildString {
            append(formatEvent("SRD_EXECUTOR_ENTER", ctx))
            append(" queueWaitMs=").append(queueWaitMs)
        }

    private fun formatEvent(event: String, ctx: Context): String {
        val thread = Thread.currentThread()
        return buildString {
            append(event)
            append(" session=").append(ctx.sessionId)
            append(" edgeKey=").append(ctx.edgeKey)
            append(" edge=").append(ctx.edgeLabel)
            append(" remote=").append(ctx.remoteModuleId)
            append(" pcGeneration=").append(ctx.pcGeneration)
            ctx.conferenceGeneration?.let { append(" conferenceGeneration=").append(it) }
            append(" executor=").append(ctx.executorName)
            append(" thread=").append(thread.name)
            append(" tid=").append(thread.id)
            append(ctx.lineageFields())
        }
    }
}
