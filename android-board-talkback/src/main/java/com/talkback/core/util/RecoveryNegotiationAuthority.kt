package com.talkback.core.util

/** ADR-0037 Phase 3.2 — production negotiation owner + glare resolution (pure rules). */
object RecoveryNegotiationAuthority {

    data class RecoveryNegotiationKey(
        val sessionId: String,
        val edgeModuleId: String,
        val recoveryEpisodeId: Long
    )

    data class OwnerElectionInput(
        val key: RecoveryNegotiationKey,
        val localModuleId: String,
        val remoteModuleId: String,
        val existingTransactionOwnerModuleId: String?,
        val recoveryCoordinatorOwnerModuleId: String?
    )

    data class OwnerResolution(
        val candidateOwners: List<String>,
        val negotiationOwnerModuleId: String,
        val rule: RecoveryNegotiationObservation.OwnerRule
    )

    enum class WireOwnerValidation {
        OK,
        BOOTSTRAP_ADOPT,
        CONFLICT
    }

    data class WireOwnerResult(
        val validation: WireOwnerValidation,
        val canonicalOwner: String,
        val wireOwner: String?,
        val conflictOwner: String?
    )

    enum class GlareResolution {
        NO_GLARE,
        KEEP_LOCAL,
        ACCEPT_REMOTE,
        REJECT_STALE
    }

    /** C: attempt-lineage coordinator bootstrap before wire owner (A). */
    fun bootstrapCoordinatorOwner(
        localModuleId: String,
        remoteModuleId: String,
        initiatesReattach: Boolean,
        recoveryViaInboundReattach: Boolean = false
    ): String = if (initiatesReattach || recoveryViaInboundReattach) {
        localModuleId
    } else {
        remoteModuleId
    }

    fun resolveOwner(input: OwnerElectionInput): OwnerResolution {
        val candidates = buildList {
            input.existingTransactionOwnerModuleId?.let { add(it) }
            input.recoveryCoordinatorOwnerModuleId?.let { if (!contains(it)) add(it) }
            if (!contains(input.localModuleId)) add(input.localModuleId)
            if (!contains(input.remoteModuleId)) add(input.remoteModuleId)
        }
        val (selected, rule) = when {
            input.existingTransactionOwnerModuleId != null ->
                input.existingTransactionOwnerModuleId to RecoveryNegotiationObservation.OwnerRule.existing_owner
            input.recoveryCoordinatorOwnerModuleId != null ->
                input.recoveryCoordinatorOwnerModuleId to RecoveryNegotiationObservation.OwnerRule.recovery_coordinator
            else -> {
                val tie = if (input.localModuleId > input.remoteModuleId) {
                    input.localModuleId
                } else {
                    input.remoteModuleId
                }
                tie to RecoveryNegotiationObservation.OwnerRule.module_tiebreaker
            }
        }
        return OwnerResolution(candidates, selected, rule)
    }

  fun validateWireOwner(
        input: OwnerElectionInput,
        wireOwnerModuleId: String?
    ): WireOwnerResult {
        val canonical = resolveOwner(input)
        val wire = wireOwnerModuleId?.takeIf { it.isNotBlank() }
        if (wire == null) {
            return WireOwnerResult(
                validation = WireOwnerValidation.BOOTSTRAP_ADOPT,
                canonicalOwner = canonical.negotiationOwnerModuleId,
                wireOwner = null,
                conflictOwner = null
            )
        }
        if (wire == canonical.negotiationOwnerModuleId) {
            return WireOwnerResult(
                validation = WireOwnerValidation.OK,
                canonicalOwner = canonical.negotiationOwnerModuleId,
                wireOwner = wire,
                conflictOwner = null
            )
        }
        return WireOwnerResult(
            validation = WireOwnerValidation.CONFLICT,
            canonicalOwner = canonical.negotiationOwnerModuleId,
            wireOwner = wire,
            conflictOwner = wire
        )
    }

    fun isGlare(
        localSignalingState: String?,
        localDescType: String?,
        remoteDescType: String?
    ): Boolean {
        return localSignalingState.equals("HAVE_LOCAL_OFFER", ignoreCase = true) &&
            localDescType.equals("OFFER", ignoreCase = true) &&
            remoteDescType.equals("OFFER", ignoreCase = true)
    }

    fun resolveGlare(
        localModuleId: String,
        localOwner: String,
        remoteOwner: String,
        localSignalingState: String?,
        localDescType: String?,
        remoteDescType: String?,
        isPoliteNegotiator: Boolean
    ): GlareResolution {
        if (!isGlare(localSignalingState, localDescType, remoteDescType)) {
            return GlareResolution.NO_GLARE
        }
        if (localOwner != remoteOwner) {
            return if (localModuleId == localOwner) {
                GlareResolution.KEEP_LOCAL
            } else {
                GlareResolution.ACCEPT_REMOTE
            }
        }
        return if (isPoliteNegotiator) {
            GlareResolution.ACCEPT_REMOTE
        } else {
            GlareResolution.KEEP_LOCAL
        }
    }
}