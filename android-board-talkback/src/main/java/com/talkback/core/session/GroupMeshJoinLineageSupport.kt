package com.talkback.core.session

/**
 * JOIN/INVITE offerer binding may only attach to a live GROUP PC identity.
 * Fenced lineages cannot be revived by a late JOIN.
 */
object GroupMeshJoinLineageSupport {
    enum class OffererDecision {
        BIND,
        SKIP_NOT_GROUP,
        SKIP_NO_IDENTITY,
        SKIP_STALE_ENGINE,
    }

    enum class AnswererDecision {
        BIND,
        SKIP_NOT_GROUP,
        SKIP_NO_LINEAGE,
        SKIP_NO_IDENTITY,
        SKIP_STALE_ENGINE,
        SKIP_RESURRECT,
    }

    data class OffererFacts(
        val groupSession: Boolean,
        val producerIsLiveEngine: Boolean,
        val pcGeneration: Long?,
        val pcHash: Int?,
    )

    data class AnswererFacts(
        val groupSession: Boolean,
        val producerIsLiveEngine: Boolean,
        val pcGeneration: Long?,
        val pcHash: Int?,
        val offerLineageId: String?,
        val existingState: GroupOfferBinding.BindingState?,
    )

    fun decideOffererBind(facts: OffererFacts): OffererDecision {
        if (!facts.groupSession) return OffererDecision.SKIP_NOT_GROUP
        if (!facts.producerIsLiveEngine) return OffererDecision.SKIP_STALE_ENGINE
        if (facts.pcGeneration == null || facts.pcHash == null) {
            return OffererDecision.SKIP_NO_IDENTITY
        }
        return OffererDecision.BIND
    }

    fun decideAnswererBind(facts: AnswererFacts): AnswererDecision {
        if (!facts.groupSession) return AnswererDecision.SKIP_NOT_GROUP
        if (facts.offerLineageId.isNullOrBlank() ||
            facts.offerLineageId == GroupPcLineageWire.UNKNOWN_LINEAGE
        ) {
            return AnswererDecision.SKIP_NO_LINEAGE
        }
        if (!facts.producerIsLiveEngine) return AnswererDecision.SKIP_STALE_ENGINE
        if (facts.pcGeneration == null || facts.pcHash == null) {
            return AnswererDecision.SKIP_NO_IDENTITY
        }
        if (facts.existingState == GroupOfferBinding.BindingState.FENCED ||
            facts.existingState == GroupOfferBinding.BindingState.RELEASED
        ) {
            return AnswererDecision.SKIP_RESURRECT
        }
        return AnswererDecision.BIND
    }
}
