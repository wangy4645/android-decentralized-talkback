package com.talkback.core.session

/**
 * ADR-0057 GPLB — offer ↔ PC lineage binding lifecycle and correlation gates.
 */
object GroupPcLineageBindingSupport {

    enum class CorrelationResult {
        /** Wire lineage matches a LIVE binding on the expected PC generation. */
        CURRENT,
        /** Binding exists but is fenced/terminal or PC generation no longer matches. */
        STALE,
        /** Missing / unknown wire lineage or no matching binding. */
        UNKNOWN,
    }

    data class CorrelationOutcome(
        val result: CorrelationResult,
        val binding: GroupOfferBinding? = null,
    )

    fun recordOffererBinding(
        session: TalkbackSession,
        remoteModuleId: String,
        offerLineageId: String,
        pcLineage: Long,
        issuedAtMs: Long = System.currentTimeMillis(),
    ): GroupOfferBinding {
        fenceLiveBindingsForPeer(
            session = session,
            remoteModuleId = remoteModuleId,
            reason = "SUPERSEDED_BY_OFFER",
            excludeLineageId = offerLineageId,
        )
        val binding =
            GroupOfferBinding(
                sessionId = session.id,
                remoteModuleId = remoteModuleId,
                offerLineageId = offerLineageId,
                pcLineage = pcLineage,
                issuedAtMs = issuedAtMs,
                state = GroupOfferBinding.BindingState.LIVE,
                role = GroupOfferBinding.BindingRole.OFFERER,
            )
        session.groupOfferBindingsByKey[binding.storageKey()] = binding
        return binding
    }

    fun recordAnswererBinding(
        session: TalkbackSession,
        remoteModuleId: String,
        offerLineageId: String,
        pcLineage: Long,
        issuedAtMs: Long = System.currentTimeMillis(),
    ): GroupOfferBinding {
        fenceLiveBindingsForPeer(
            session = session,
            remoteModuleId = remoteModuleId,
            reason = "SUPERSEDED_BY_ANSWERER_APPLY",
            excludeLineageId = offerLineageId,
        )
        val binding =
            GroupOfferBinding(
                sessionId = session.id,
                remoteModuleId = remoteModuleId,
                offerLineageId = offerLineageId,
                pcLineage = pcLineage,
                issuedAtMs = issuedAtMs,
                state = GroupOfferBinding.BindingState.LIVE,
                role = GroupOfferBinding.BindingRole.ANSWERER,
            )
        session.groupOfferBindingsByKey[binding.storageKey()] = binding
        return binding
    }

    fun binding(
        session: TalkbackSession,
        remoteModuleId: String,
        offerLineageId: String,
    ): GroupOfferBinding? =
        session.groupOfferBindingsByKey[
            GroupOfferBinding.storageKey(session.id, remoteModuleId, offerLineageId),
        ]

    fun liveBindingForPcLineage(
        session: TalkbackSession,
        remoteModuleId: String,
        pcLineage: Long,
    ): GroupOfferBinding? =
        session.groupOfferBindingsByKey.values.firstOrNull {
            it.remoteModuleId == remoteModuleId &&
                it.pcLineage == pcLineage &&
                it.state == GroupOfferBinding.BindingState.LIVE
        }

    fun correlateWireLineage(
        session: TalkbackSession,
        remoteModuleId: String,
        wireOfferLineageId: String,
        currentPcLineage: Long?,
    ): CorrelationOutcome {
        if (wireOfferLineageId.isBlank() || wireOfferLineageId == GroupPcLineageWire.UNKNOWN_LINEAGE) {
            return CorrelationOutcome(CorrelationResult.UNKNOWN)
        }
        val existing = binding(session, remoteModuleId, wireOfferLineageId)
            ?: return CorrelationOutcome(CorrelationResult.UNKNOWN)
        if (existing.state != GroupOfferBinding.BindingState.LIVE) {
            return CorrelationOutcome(CorrelationResult.STALE, existing)
        }
        // GPLB-L3: signalingAccepted does not fence media binding; LIVE suffices for ICE/ACCEPT.
        val pcLineage = currentPcLineage ?: return CorrelationOutcome(CorrelationResult.STALE, existing)
        if (existing.pcLineage != pcLineage) {
            return CorrelationOutcome(CorrelationResult.STALE, existing)
        }
        return CorrelationOutcome(CorrelationResult.CURRENT, existing)
    }

    /** GPLB-L2: GROUP_ACCEPT / SRD success completes signaling only; binding stays LIVE. */
    fun markSignalingAccepted(session: TalkbackSession, binding: GroupOfferBinding) {
        if (binding.signalingAccepted) return
        session.groupOfferBindingsByKey[binding.storageKey()] =
            binding.copy(signalingAccepted = true)
    }

    /** GPLB-L4: explicit media-lineage release (not triggered by GROUP_ACCEPT alone). */
    fun releaseBinding(session: TalkbackSession, binding: GroupOfferBinding) {
        if (binding.state == GroupOfferBinding.BindingState.RELEASED) return
        session.groupOfferBindingsByKey[binding.storageKey()] =
            binding.copy(state = GroupOfferBinding.BindingState.RELEASED)
    }

    fun fenceBinding(
        session: TalkbackSession,
        binding: GroupOfferBinding,
        reason: String = "FENCED",
    ) {
        if (binding.state != GroupOfferBinding.BindingState.LIVE) return
        session.groupOfferBindingsByKey[binding.storageKey()] =
            binding.copy(state = GroupOfferBinding.BindingState.FENCED)
    }

    private fun fenceLiveBindingsForPeer(
        session: TalkbackSession,
        remoteModuleId: String,
        reason: String,
        excludeLineageId: String,
    ) {
        session.groupOfferBindingsByKey.values
            .filter {
                it.sessionId == session.id &&
                    it.remoteModuleId == remoteModuleId &&
                    it.offerLineageId != excludeLineageId &&
                    it.state == GroupOfferBinding.BindingState.LIVE
            }
            .forEach { live ->
                session.groupOfferBindingsByKey[live.storageKey()] =
                    live.copy(state = GroupOfferBinding.BindingState.FENCED)
            }
    }
}
