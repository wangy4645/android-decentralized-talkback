package com.talkback.core.conference.session.profile01

import java.util.concurrent.ConcurrentHashMap

/**
 * Production membership fact convergence — authoritative generation per conference.
 *
 * Does not decide join/leave policy; only chains verified facts to a single current head.
 */
class Profile01MembershipConvergenceRegistry {
    private val generations = ConcurrentHashMap<String, Profile01MembershipGeneration>()
    private val pending = ConcurrentHashMap<String, MutableList<Profile01WireMembershipFact>>()
    private val branchConflicts = ConcurrentHashMap<String, MutableSet<ByteArray>>()

    fun current(conferenceId: String): Profile01MembershipGeneration? = generations[conferenceId]

    fun seedFromCreation(wire: Profile01WireSessionFact): Profile01MembershipApplyResult {
        val existing = generations[wire.conferenceId]
        if (existing != null) {
            if (existing.generationFactDigest.contentEquals(wire.factDigest)) {
                return Profile01MembershipApplyResult.Idempotent(existing)
            }
            if (wire.conferenceEpoch < existing.conferenceEpoch) {
                return Profile01MembershipApplyResult.Superseded("STALE_CREATION_EPOCH")
            }
        }
        val generation =
            Profile01MembershipGeneration(
                conferenceId = wire.conferenceId,
                conferenceEpoch = wire.conferenceEpoch,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
                generationFactDigest = wire.factDigest.copyOf(),
                creationFactDigest = wire.factDigest.copyOf(),
                members = wire.membershipView.map { member ->
                    Profile01WireMembershipMember(
                        moduleId = member.moduleId,
                        membershipIncarnationId = member.membershipIncarnationId.copyOf(),
                    )
                },
                mediaKeyCommitment = wire.mediaKeyCommitment.copyOf(),
            )
        generations[wire.conferenceId] = generation
        drainPending(wire.conferenceId)
        return Profile01MembershipApplyResult.Accepted(generation)
    }

    fun applyMembership(wire: Profile01WireMembershipFact): Profile01MembershipApplyResult {
        val current = generations[wire.conferenceId]
        if (current == null) {
            queuePending(wire)
            return Profile01MembershipApplyResult.ChainPending("CREATION_NOT_CONVERGED")
        }
        if (wire.conferenceEpoch < current.conferenceEpoch) {
            return Profile01MembershipApplyResult.Superseded("STALE_CONFERENCE_EPOCH")
        }
        if (wire.factDigest.contentEquals(current.generationFactDigest)) {
            return Profile01MembershipApplyResult.Idempotent(current)
        }
        if (wire.membershipVersion < current.membershipVersion) {
            return Profile01MembershipApplyResult.Superseded("STALE_MEMBERSHIP_VERSION")
        }
        if (wire.membershipVersion == current.membershipVersion) {
            recordBranchConflict(wire.conferenceId, wire.factDigest)
            return Profile01MembershipApplyResult.BranchConflict("MEMBERSHIP_BRANCH_CONFLICT")
        }
        if (!wire.previousMembershipDigest.contentEquals(current.generationFactDigest)) {
            queuePending(wire)
            return Profile01MembershipApplyResult.ChainPending("PREDECESSOR_NOT_CURRENT_HEAD")
        }
        val generation =
            Profile01MembershipGeneration(
                conferenceId = wire.conferenceId,
                conferenceEpoch = wire.conferenceEpoch,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
                generationFactDigest = wire.factDigest.copyOf(),
                creationFactDigest = current.creationFactDigest.copyOf(),
                members = wire.members.map { member ->
                    Profile01WireMembershipMember(
                        moduleId = member.moduleId,
                        membershipIncarnationId = member.membershipIncarnationId.copyOf(),
                    )
                },
                mediaKeyCommitment = wire.mediaKeyCommitment.copyOf(),
            )
        generations[wire.conferenceId] = generation
        drainPending(wire.conferenceId)
        return Profile01MembershipApplyResult.Accepted(generation)
    }

    fun clear() {
        generations.clear()
        pending.clear()
        branchConflicts.clear()
    }

    private fun queuePending(wire: Profile01WireMembershipFact) {
        val list = pending.getOrPut(wire.conferenceId) { mutableListOf() }
        if (list.none { it.factDigest.contentEquals(wire.factDigest) }) {
            list += wire
        }
    }

    private fun recordBranchConflict(conferenceId: String, factDigest: ByteArray) {
        val set = branchConflicts.getOrPut(conferenceId) { mutableSetOf() }
        set += factDigest.copyOf()
    }

    private fun drainPending(conferenceId: String) {
        val queued = pending.remove(conferenceId) ?: return
        var progressed = true
        while (progressed) {
            progressed = false
            val current = generations[conferenceId] ?: return
            val ready =
                queued.firstOrNull { wire ->
                    wire.membershipVersion > current.membershipVersion &&
                        wire.previousMembershipDigest.contentEquals(current.generationFactDigest)
                }
            if (ready != null) {
                queued.remove(ready)
                applyMembership(ready)
                progressed = true
            }
        }
        if (queued.isNotEmpty()) {
            pending[conferenceId] = queued
        }
    }
}

sealed class Profile01MembershipApplyResult {
    data class Accepted(val generation: Profile01MembershipGeneration) : Profile01MembershipApplyResult()

    data class Idempotent(val generation: Profile01MembershipGeneration) : Profile01MembershipApplyResult()

    data class ChainPending(val reason: String) : Profile01MembershipApplyResult()

    data class BranchConflict(val reason: String) : Profile01MembershipApplyResult()

    data class Superseded(val reason: String) : Profile01MembershipApplyResult()
}
