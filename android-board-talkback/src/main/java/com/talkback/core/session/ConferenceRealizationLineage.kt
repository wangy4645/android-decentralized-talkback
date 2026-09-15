package com.talkback.core.session

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * IA-001: GCT conference realization offer↔answer correlation (observation only).
 * Does not change pending drain, createOffer count, or native SRD behavior.
 */
object ConferenceRealizationLineage {
    const val UNKNOWN = "OFFER_LINEAGE_UNKNOWN"
    const val KEY_ANSWER_SDP = "tbAnswerSdp"
    const val KEY_OFFER_LINEAGE = "realizationOfferLineageId"
    const val KEY_ATTEMPT = "realizationAttemptId"

    private val offerSeq = AtomicLong(0L)
    private val attemptSeq = AtomicLong(0L)
    private val bound = ThreadLocal<Bound?>()

    data class Bound(
        val offerLineageId: String,
        val realizationAttemptId: String,
        val pcGeneration: Long
    )

    data class AnswerParse(
        val sdp: String,
        val offerLineageId: String,
        val realizationAttemptId: String
    )

    enum class Correlation {
        MATCH,
        MISMATCH,
        OFFER_LINEAGE_UNKNOWN
    }

    fun nextOfferLineageId(): String = "CR${offerSeq.incrementAndGet()}"

    fun nextAttemptId(): String = "RA${attemptSeq.incrementAndGet()}"

    fun <T> bind(boundFields: Bound, block: () -> T): T {
        bound.set(boundFields)
        try {
            return block()
        } finally {
            bound.remove()
        }
    }

    fun boundOrNull(): Bound? = bound.get()

    fun negotiationSuffix(): String {
        val current = bound.get() ?: return ""
        return " offerLineageId=${current.offerLineageId}" +
            " realizationAttemptId=${current.realizationAttemptId}" +
            " pcGeneration=${current.pcGeneration}"
    }

    fun correlate(answerLineageId: String, hostLocalOfferLineageId: String?): Correlation {
        if (answerLineageId == UNKNOWN || answerLineageId.isBlank()) {
            return Correlation.OFFER_LINEAGE_UNKNOWN
        }
        val host = hostLocalOfferLineageId?.takeIf { it.isNotBlank() && it != UNKNOWN }
            ?: return Correlation.OFFER_LINEAGE_UNKNOWN
        return if (answerLineageId == host) Correlation.MATCH else Correlation.MISMATCH
    }

    fun encodeAnswer(sdp: String, offerLineageId: String?, realizationAttemptId: String?): String {
        if (sdp.isBlank()) return sdp
        val lineage = offerLineageId?.takeIf { it.isNotBlank() && it != UNKNOWN } ?: return sdp
        return JSONObject()
            .put(KEY_ANSWER_SDP, sdp)
            .put(KEY_OFFER_LINEAGE, lineage)
            .put(KEY_ATTEMPT, realizationAttemptId?.takeIf { it.isNotBlank() } ?: UNKNOWN)
            .toString()
    }

    fun parseAnswer(payload: String): AnswerParse {
        if (payload.isBlank()) {
            return AnswerParse(sdp = "", offerLineageId = UNKNOWN, realizationAttemptId = UNKNOWN)
        }
        val json = runCatching { JSONObject(payload) }.getOrNull()
        if (json != null && json.has(KEY_ANSWER_SDP)) {
            return AnswerParse(
                sdp = json.optString(KEY_ANSWER_SDP),
                offerLineageId = json.optString(KEY_OFFER_LINEAGE).takeIf { it.isNotBlank() }
                    ?: UNKNOWN,
                realizationAttemptId = json.optString(KEY_ATTEMPT).takeIf { it.isNotBlank() }
                    ?: UNKNOWN
            )
        }
        return AnswerParse(
            sdp = payload,
            offerLineageId = UNKNOWN,
            realizationAttemptId = UNKNOWN
        )
    }

    fun formatEvent(
        stage: String,
        sessionId: String,
        remoteModuleId: String,
        offerLineageId: String,
        realizationAttemptId: String,
        pcGeneration: Long? = null,
        pcHash: Int? = null,
        extra: String? = null
    ): String = buildString {
        append("CONFERENCE_OFFER_LINEAGE stage=").append(stage)
        append(" session=").append(sessionId)
        append(" remote=").append(remoteModuleId)
        append(" offerLineageId=").append(offerLineageId.ifBlank { UNKNOWN })
        append(" realizationAttemptId=").append(realizationAttemptId.ifBlank { UNKNOWN })
        pcGeneration?.let { append(" pcGeneration=").append(it) }
        pcHash?.let { append(" pcHash=").append(it) }
        extra?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
    }
}
