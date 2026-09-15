package com.talkback.core.conference.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Holds authoritative control-plane facts for adapter/port reads.
 *
 * Rejects stale/superseded publishes; does not drive media lifecycle.
 */
class ConferenceSessionMediaControlFactRegistry {
    private data class MemberSlot(
        var current: ConferenceMediaMemberControlFact? = null,
        var previous: ConferenceMediaMemberControlFact? = null,
        var replaceConsumed: Boolean = false,
    )

    private data class SessionSlot(
        var current: ConferenceMediaSessionControlFact? = null,
        val members: ConcurrentHashMap<String, MemberSlot> = ConcurrentHashMap(),
    )

    private val sessions = ConcurrentHashMap<String, SessionSlot>()

    fun publishSession(fact: ConferenceMediaSessionControlFact): ControlFactPublishOutcome {
        if (!ConferenceSessionMediaFactAdapter.isPublishableSession(fact)) {
            return ControlFactPublishOutcome.REJECTED_SUPERSEDED
        }
        val slot = sessions.getOrPut(fact.conferenceId) { SessionSlot() }
        val current = slot.current
        if (current != null) {
            if (fact.factGeneration < current.factGeneration) {
                return ControlFactPublishOutcome.REJECTED_STALE
            }
            if (fact.factGeneration == current.factGeneration &&
                sessionFactsEqual(fact, current)
            ) {
                return ControlFactPublishOutcome.IDEMPOTENT
            }
            if (fact.conferenceEpoch < current.conferenceEpoch ||
                fact.membershipVersion < current.membershipVersion
            ) {
                return ControlFactPublishOutcome.REJECTED_STALE
            }
        }
        slot.current = fact
        return ControlFactPublishOutcome.ACCEPTED
    }

    fun publishMember(fact: ConferenceMediaMemberControlFact): ControlFactPublishOutcome {
        val session = sessions[fact.conferenceId]?.current
            ?: return ControlFactPublishOutcome.REJECTED_NO_SESSION
        if (!ConferenceSessionMediaFactAdapter.isPublishableMember(session, fact)) {
            return if (fact.superseded) {
                ControlFactPublishOutcome.REJECTED_SUPERSEDED
            } else {
                ControlFactPublishOutcome.REJECTED_STALE
            }
        }
        val slot = sessions[fact.conferenceId]!!.members.getOrPut(fact.moduleId) { MemberSlot() }
        val current = slot.current
        if (current != null) {
            if (fact.factGeneration < current.factGeneration) {
                return ControlFactPublishOutcome.REJECTED_STALE
            }
            if (fact.factGeneration == current.factGeneration &&
                ConferenceSessionMediaFactAdapter.memberFactsEqual(fact, current)
            ) {
                return ControlFactPublishOutcome.IDEMPOTENT
            }
            if (fact.membershipIncarnationId < current.membershipIncarnationId) {
                return ControlFactPublishOutcome.REJECTED_STALE
            }
            if (fact.membershipIncarnationId == current.membershipIncarnationId &&
                !ConferenceSessionMediaFactAdapter.memberFactsEqual(fact, current)
            ) {
                return ControlFactPublishOutcome.REJECTED_INCOHERENT
            }
        }
        if (current != null && fact.membershipIncarnationId > current.membershipIncarnationId) {
            slot.previous = current
            slot.replaceConsumed = false
        }
        slot.current = fact
        return ControlFactPublishOutcome.ACCEPTED
    }

    fun session(conferenceId: String): ConferenceMediaSessionControlFact? =
        sessions[conferenceId]?.current

    fun member(
        conferenceId: String,
        moduleId: String,
    ): ConferenceMediaMemberControlFact? =
        sessions[conferenceId]?.members?.get(moduleId)?.current

    /** Module ids with a member fact matching the current session head. */
    fun memberModuleIdsAtHead(conferenceId: String): Set<String> {
        val session = sessions[conferenceId]?.current ?: return emptySet()
        return sessions[conferenceId]!!
            .members
            .filter { (_, slot) ->
                val member = slot.current
                member != null && ConferenceSessionMediaFactAdapter.memberMatchesSession(session, member)
            }
            .keys
    }

    fun memberReplacePair(
        conferenceId: String,
        moduleId: String,
    ): Pair<ConferenceMediaMemberControlFact, ConferenceMediaMemberControlFact>? {
        val sessionSlot = sessions[conferenceId] ?: return null
        val memberSlot = sessionSlot.members[moduleId] ?: return null
        if (memberSlot.replaceConsumed) return null
        val previous = memberSlot.previous ?: return null
        val current = memberSlot.current ?: return null
        if (current.membershipIncarnationId <= previous.membershipIncarnationId) return null
        return Pair(previous, current)
    }

    fun consumeMemberReplace(
        conferenceId: String,
        moduleId: String,
    ) {
        val memberSlot = sessions[conferenceId]?.members?.get(moduleId) ?: return
        memberSlot.replaceConsumed = true
        memberSlot.previous = null
    }

    fun clearSession(conferenceId: String) {
        sessions.remove(conferenceId)
    }

    private fun sessionFactsEqual(
        left: ConferenceMediaSessionControlFact,
        right: ConferenceMediaSessionControlFact,
    ): Boolean =
        left.conferenceEpoch == right.conferenceEpoch &&
            left.membershipVersion == right.membershipVersion &&
            left.mediaKeyEpoch == right.mediaKeyEpoch &&
            left.channelId == right.channelId &&
            left.factGeneration == right.factGeneration &&
            left.masterKey.contentEquals(right.masterKey) &&
            left.masterSalt.contentEquals(right.masterSalt) &&
            left.keyContextHint64.contentEquals(right.keyContextHint64)
}
