package com.talkback.core.webrtc

/**
 * G2-RCA2 Step 2b: native SRD(ANSWER) admission invariant.
 *
 * [RealWebRtcAudioEngine] evaluates this immediately before native setRemoteDescription(ANSWER)
 * and MUST skip the native call when [SrdAdmissionDecision.admit] is false (field: STALE answer
 * → SIGABRT on signaling_thread).
 */
internal data class SrdAdmissionInput(
    val signalingState: String,
    val localDescriptionType: String?,
    val remoteDescriptionType: String?,
    val expectedPcGeneration: Long?,
    val actualPcGeneration: Long?,
    val outstandingOfferLineageId: String?,
    val answerOfferLineageId: String?,
    val latestAdmittedTaskId: Long?,
    val taskId: Long?,
    val localOfferIceUfrag: String?,
    val answerIceUfrag: String?,
    val answerIcePwdFingerprint: String?,
    val previouslyAppliedRemoteIceUfrag: String?,
    val remoteDescriptionAlreadyApplied: Boolean,
    val outstandingOfferAgeMs: Long,
    val queuedIceCount: Int,
    val iceIngressDuringSrd: Boolean,
)

internal data class SrdAdmissionDecision(
    val answerMatchesOutstandingOffer: Boolean,
    val pcGenerationCurrent: Boolean,
    val taskCurrent: Boolean,
    val signalingStateAllowed: Boolean,
    val iceCredentialMatches: Boolean,
    val outstandingOfferAgeMs: Long,
    val queuedIceCount: Int,
    val iceIngressDuringSrd: Boolean,
    val violations: List<String>,
) {
    val admit: Boolean get() = violations.isEmpty()

    fun formatFields(): String =
        "admit=$admit " +
            "answerMatchesOutstandingOffer=$answerMatchesOutstandingOffer " +
            "pcGenerationCurrent=$pcGenerationCurrent " +
            "taskCurrent=$taskCurrent " +
            "signalingStateAllowed=$signalingStateAllowed " +
            "iceCredentialMatches=$iceCredentialMatches " +
            "outstandingOfferAgeMs=$outstandingOfferAgeMs " +
            "queuedIceCount=$queuedIceCount " +
            "iceIngressDuringSrd=$iceIngressDuringSrd " +
            "violations=${if (violations.isEmpty()) "NONE" else violations.joinToString(",")}"

    companion object {
        const val DUPLICATE_ANSWER = "DUPLICATE_ANSWER"
        const val STALE_OR_MISMATCHED_ANSWER = "STALE_OR_MISMATCHED_ANSWER"
        const val PC_GENERATION_STALE = "PC_GENERATION_STALE"
        const val SUPERSEDED_TASK = "SUPERSEDED_TASK"
        const val SIGNALING_STATE_INVALID = "SIGNALING_STATE_INVALID"
        const val SDP_CREDENTIAL_MISMATCH = "SDP_CREDENTIAL_MISMATCH"

        fun evaluate(input: SrdAdmissionInput): SrdAdmissionDecision {
            val violations = mutableListOf<String>()

            // A legal answer lands on a PC that still holds exactly its own outstanding offer.
            val signalingStateAllowed = input.signalingState == "HAVE_LOCAL_OFFER" &&
                input.localDescriptionType == "OFFER" &&
                input.remoteDescriptionType == null
            if (!signalingStateAllowed) violations += SIGNALING_STATE_INVALID

            val lineageMatches = input.outstandingOfferLineageId != null &&
                input.outstandingOfferLineageId == input.answerOfferLineageId
            val answerMatchesOutstandingOffer =
                lineageMatches && !input.remoteDescriptionAlreadyApplied
            if (input.remoteDescriptionAlreadyApplied) violations += DUPLICATE_ANSWER
            if (!lineageMatches) violations += STALE_OR_MISMATCHED_ANSWER

            val pcGenerationCurrent = input.expectedPcGeneration == null ||
                input.actualPcGeneration == null ||
                input.expectedPcGeneration == input.actualPcGeneration
            if (!pcGenerationCurrent) violations += PC_GENERATION_STALE

            val taskCurrent = input.latestAdmittedTaskId == null ||
                input.taskId == null ||
                input.latestAdmittedTaskId == input.taskId
            if (!taskCurrent) violations += SUPERSEDED_TASK

            // The answer must carry credentials, must not echo our own offer's ufrag, and must
            // agree with any remote ufrag already installed on this PeerConnection generation.
            val iceCredentialMatches = !input.answerIceUfrag.isNullOrBlank() &&
                !input.answerIcePwdFingerprint.isNullOrBlank() &&
                input.answerIceUfrag != input.localOfferIceUfrag &&
                (input.previouslyAppliedRemoteIceUfrag == null ||
                    input.previouslyAppliedRemoteIceUfrag == input.answerIceUfrag)
            if (!iceCredentialMatches) violations += SDP_CREDENTIAL_MISMATCH

            return SrdAdmissionDecision(
                answerMatchesOutstandingOffer = answerMatchesOutstandingOffer,
                pcGenerationCurrent = pcGenerationCurrent,
                taskCurrent = taskCurrent,
                signalingStateAllowed = signalingStateAllowed,
                iceCredentialMatches = iceCredentialMatches,
                outstandingOfferAgeMs = input.outstandingOfferAgeMs,
                queuedIceCount = input.queuedIceCount,
                iceIngressDuringSrd = input.iceIngressDuringSrd,
                violations = violations.distinct(),
            )
        }
    }
}
