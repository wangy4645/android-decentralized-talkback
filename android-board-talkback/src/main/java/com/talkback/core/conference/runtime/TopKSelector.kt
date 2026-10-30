package com.talkback.core.conference.runtime

/**
 * Profile 03 Q5 Top-K selection (E2b-02).
 *
 * C-E2B02-02: Hold/hysteresis apply only among still-active V=1 candidates.
 * V=0 immediately loses active-candidate eligibility and MUST NOT retain a seat by Hold.
 */
class TopKSelector {
    data class SelectionState(
        val members: List<TopKMember> = emptyList(),
        /** When each identity last entered / was retained in Top-K (hold clock). */
        val holdStartedAtMs: Map<String, Long> = emptyMap(),
    )

    fun select(
        nowMs: Long,
        registry: AdmittedMediaSourceRegistry,
        voice: Map<String, VoiceLevelObservation>,
        previous: SelectionState,
        localModuleIdForTopKExclusion: String? = null,
    ): SelectionState {
        data class Cand(
            val identity: String,
            val incarnationId: Long,
            val audioLevel: Int,
            val wasInTopK: Boolean,
        )

        val eligible = mutableListOf<Cand>()
        for ((identity, inst) in registry.installedSnapshot()) {
            if (localModuleIdForTopKExclusion != null && identity == localModuleIdForTopKExclusion) {
                continue
            }
            if (!inst.isExecutable) continue
            val obs = voice[identity] ?: continue
            if (obs.incarnationId != inst.source.incarnationId) continue
            if (!obs.voiceActive) continue // V=0: not an active candidate (Hold cannot retain)
            eligible +=
                Cand(
                    identity = identity,
                    incarnationId = inst.source.incarnationId,
                    audioLevel = obs.audioLevel,
                    wasInTopK = previous.members.any { it.sourceIdentity == identity },
                )
        }

        val ranked =
            eligible.sortedWith(
                compareBy<Cand> { it.audioLevel }
                    .thenByDescending { it.wasInTopK }
                    .thenBy { it.identity },
            )

        var provisional = ranked.take(MediaRuntimeConstants.TOP_K).toMutableList()

        // Hold protection among V=1 only: a held incumbent displaced by ranking
        // is restored unless the challenger is >= TopKHysteresisLevel louder.
        for (incumbent in previous.members) {
            val cand = eligible.find { it.identity == incumbent.sourceIdentity } ?: continue
            if (provisional.any { it.identity == cand.identity }) continue
            val holdStart = previous.holdStartedAtMs[cand.identity] ?: continue
            if (nowMs >= holdStart + MediaRuntimeConstants.TOP_K_HOLD_MS) continue
            if (provisional.isEmpty()) {
                provisional += cand
                continue
            }
            val weakest =
                provisional.maxWithOrNull(
                    compareBy<Cand> { it.audioLevel }
                        .thenBy { if (it.wasInTopK) 0 else 1 }
                        .thenByDescending { it.identity },
                ) ?: continue
            val loudEnough =
                weakest.audioLevel <= cand.audioLevel - MediaRuntimeConstants.TOP_K_HYSTERESIS_LEVEL
            if (loudEnough) continue
            provisional.remove(weakest)
            provisional += cand
            provisional =
                provisional
                    .sortedWith(
                        compareBy<Cand> { it.audioLevel }
                            .thenByDescending { it.wasInTopK }
                            .thenBy { it.identity },
                    ).toMutableList()
        }

        val members =
            provisional.map {
                TopKMember(
                    sourceIdentity = it.identity,
                    incarnationId = it.incarnationId,
                    audioLevel = it.audioLevel,
                )
            }

        val holdStarted = previous.holdStartedAtMs.toMutableMap()
        val previousIds = previous.members.map { it.sourceIdentity }.toSet()
        for (m in members) {
            if (m.sourceIdentity !in previousIds) {
                holdStarted[m.sourceIdentity] = nowMs
            } else if (m.sourceIdentity !in holdStarted) {
                holdStarted[m.sourceIdentity] = nowMs
            }
        }
        holdStarted.keys.retainAll(members.map { it.sourceIdentity }.toSet())

        return SelectionState(members = members, holdStartedAtMs = holdStarted)
    }
}
