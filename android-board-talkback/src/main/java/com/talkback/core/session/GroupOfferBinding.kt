package com.talkback.core.session

/**
 * ADR-0057 GPLB — durable offer ↔ PC lineage binding for GROUP mesh negotiation.
 * Distinct from [OutboundGroupInviteAttempt] (delivery obligation / GIDR).
 */
data class GroupOfferBinding(
    val sessionId: String,
    val remoteModuleId: String,
    val offerLineageId: String,
    val pcLineage: Long,
    val issuedAtMs: Long,
    val state: BindingState,
    val role: BindingRole,
    /** Signaling attempt reached GROUP_ACCEPT / SRD on this lineage (orthogonal to [state]). */
    val signalingAccepted: Boolean = false,
) {
    enum class BindingState {
        /** Active media binding; may receive correlated ACCEPT / ICE until GPLB-L4 release. */
        LIVE,
        /** Superseded by a newer offer lineage; correlated signaling must be fenced. */
        FENCED,
        /** Explicit media-lineage release (engine/PC/session invalidation — not GROUP_ACCEPT). */
        RELEASED,
    }

    enum class BindingRole {
        /** Local offer originated this binding (GIE / offerer). */
        OFFERER,
        /** Remote offer applied on local engine (IGIE / answerer). */
        ANSWERER,
    }

    fun storageKey(): String = storageKey(sessionId, remoteModuleId, offerLineageId)

    companion object {
        fun storageKey(sessionId: String, remoteModuleId: String, offerLineageId: String): String =
            "$sessionId|$remoteModuleId|$offerLineageId"
    }
}
