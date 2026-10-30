package com.talkback.core.conference.session.integration

/**
 * Classifies coordinator [reason] strings for B1-R2 post-bind SOURCE republication.
 *
 * Transport `publishToPeer` success alone does not prove the recipient could consume
 * the SOURCE fact (pre-bind ingress drops factType 9). Only reasons tied to peer
 * consumption readiness may close [SourcePostBindPublicationLedger].
 */
object Profile01SourcePostBindRepublishReason {
    fun marksConsumableDeliverySatisfied(reason: String): Boolean =
        when (reason) {
            "peer_source_declaration_wire_applied",
            "admission_ready_after_answer_settled",
            "local_shadow_wire_consumption_ready",
            -> true
            else -> false
        }
}
