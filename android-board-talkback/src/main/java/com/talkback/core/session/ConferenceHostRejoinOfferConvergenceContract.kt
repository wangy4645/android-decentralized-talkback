package com.talkback.core.session



/**

 * P14 / S4: Host rejoin offer convergence — merge duplicate pending offers and

 * resync head lineage after stale-answer mismatch without weakening [ConferenceRealizationLineage.correlate].

 */

object ConferenceHostRejoinOfferConvergenceContract {



    enum class MergeHostRejoinOfferDecision {

        /** Keep negotiating on the current host-local offer lineage. */

        COALESCE_TO_PENDING,

        /** Start or replace with a new offer lineage (legitimate new round or prior failure). */

        CREATE_NEW_LINEAGE,

    }



    data class HostOfferEdgeContext(

        val hostLocalOfferLineageId: String?,

        val pcGeneration: Long,

        val admissionHandoffActive: Boolean,

        val admissionPhase: ConferenceAdmissionPhase?,

        /** QoS / ICE path: conference media connected for this peer. */

        val peerConferenceConnected: Boolean,

        /** Host still holds an uncompleted GROUP_INVITE for [hostLocalOfferLineageId] at [pcGeneration]. */

        val hostOutstandingOfferPending: Boolean,

        /**

         * Peer is on the effective conference membership / admitted-media roster for this edge.

         * False after control-leave or prune — stale host lineage must not be reused.

         */

        val peerEligibleForHostOfferRealization: Boolean,

        /** Diagnostic only; merge is not gated on rejoin membership marking. */

        val rejoin: Boolean,

    )



    data class LineageMismatchResyncDecision(

        val rejectStaleAnswer: Boolean,

        val shouldResyncHead: Boolean,

        val resyncOfferLineageId: String?,

        val dedupeKey: String?,

    )



    private fun outstandingLineage(hostLocalOfferLineageId: String?): String? =

        hostLocalOfferLineageId?.takeIf {

            it.isNotBlank() && it != ConferenceRealizationLineage.UNKNOWN

        }



    private fun negotiationStillOpen(ctx: HostOfferEdgeContext): Boolean {

        if (ctx.admissionHandoffActive) return true

        return when (ctx.admissionPhase) {

            ConferenceAdmissionPhase.INVITED,

            ConferenceAdmissionPhase.ACCEPTING,

            ConferenceAdmissionPhase.NEGOTIATING -> true

            ConferenceAdmissionPhase.READY,

            ConferenceAdmissionPhase.FAILED,

            ConferenceAdmissionPhase.TERMINATED,

            null -> false

        }

    }



    /**

     * Outstanding lineage is actionable only while admission is live and peer is still eligible.

     */

    fun hostOfferNegotiationStillValid(ctx: HostOfferEdgeContext): Boolean {

        if (!ctx.peerEligibleForHostOfferRealization) return false

        when (ctx.admissionPhase) {

            ConferenceAdmissionPhase.FAILED,

            ConferenceAdmissionPhase.TERMINATED -> return false

            null -> {
                return ctx.hostOutstandingOfferPending &&
                    outstandingLineage(ctx.hostLocalOfferLineageId) != null
            }

            else -> Unit

        }

        if (negotiationStillOpen(ctx)) return true

        return ctx.admissionHandoffActive && ctx.hostOutstandingOfferPending

    }



    /**

     * Same session + peer + pcGeneration realization/rejoin triggers must not supersede an open offer.

     * Judgment is negotiation lifecycle + validity of outstanding lineage, not [HostOfferEdgeContext.rejoin].

     */

    fun decideMergeHostRejoinOffer(ctx: HostOfferEdgeContext): MergeHostRejoinOfferDecision {

        if (!ctx.peerEligibleForHostOfferRealization) {

            return MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE

        }

        if (!hostOfferNegotiationStillValid(ctx)) {

            return MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE

        }

        if (ctx.admissionPhase == ConferenceAdmissionPhase.FAILED ||

            ctx.admissionPhase == ConferenceAdmissionPhase.TERMINATED

        ) {

            return MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE

        }

        if (ctx.peerConferenceConnected && !ctx.admissionHandoffActive) {

            return MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE

        }

        if (outstandingLineage(ctx.hostLocalOfferLineageId) == null) {

            return MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE

        }

        if (ctx.admissionPhase == ConferenceAdmissionPhase.READY && ctx.peerConferenceConnected) {

            return MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE

        }

        if (negotiationStillOpen(ctx)) {

            return MergeHostRejoinOfferDecision.COALESCE_TO_PENDING

        }

        if (ctx.admissionHandoffActive && ctx.hostOutstandingOfferPending) {

            return MergeHostRejoinOfferDecision.COALESCE_TO_PENDING

        }

        return MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE

    }



    fun headOfferStillValidForResync(

        hostLocalOfferLineageId: String?,

        localSignalingState: String?,

        localDescriptionType: String?,

    ): Boolean {

        val head = outstandingLineage(hostLocalOfferLineageId) ?: return false

        if (head.isBlank()) return false

        if (!localSignalingState.equals("STABLE", ignoreCase = true) &&

            !localSignalingState.equals("HAVE_LOCAL_OFFER", ignoreCase = true)

        ) {

            return false

        }

        return localDescriptionType.equals("OFFER", ignoreCase = true)

    }



    /**

     * Stale answer is always rejected; resync current head once per (answer, head) pair.

     */

    fun decideLineageMismatchResync(

        answerOfferLineageId: String,

        hostLocalOfferLineageId: String?,

        headOfferValid: Boolean,

        lastResyncDedupeKey: String?,

    ): LineageMismatchResyncDecision {

        val head = outstandingLineage(hostLocalOfferLineageId)

        if (head == null || answerOfferLineageId == head) {

            return LineageMismatchResyncDecision(

                rejectStaleAnswer = answerOfferLineageId != head,

                shouldResyncHead = false,

                resyncOfferLineageId = null,

                dedupeKey = null,

            )

        }

        val dedupeKey = "$answerOfferLineageId>$head"

        val shouldResync = headOfferValid && lastResyncDedupeKey != dedupeKey

        return LineageMismatchResyncDecision(

            rejectStaleAnswer = true,

            shouldResyncHead = shouldResync,

            resyncOfferLineageId = if (shouldResync) head else null,

            dedupeKey = if (shouldResync) dedupeKey else null,

        )

    }

}


