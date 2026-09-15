package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaFact
import com.talkback.core.conference.session.ConferenceSessionMediaFactAdapter
import com.talkback.core.conference.session.ConferenceSessionMediaFactPort
import com.talkback.core.conference.session.MemberBindingFact

/**
 * Resolves Meeting sessionId → wire conferenceId for registry reads.
 * Rewrites [ConferenceSessionMediaFact.sessionId] to the Meeting session id for wiring.
 */
class MeetingProfile01AwareConferenceSessionMediaFactPort(
    private val registry: ConferenceSessionMediaControlFactRegistry,
    private val index: MeetingProfile01ConferenceSessionIndex,
    private val adapter: ConferenceSessionMediaFactAdapter = ConferenceSessionMediaFactAdapter,
) : ConferenceSessionMediaFactPort {
    override fun sessionFact(
        sessionId: String,
        channelId: String,
        rosterEpoch: Long,
    ): ConferenceSessionMediaFact? {
        val conferenceId = resolveConferenceId(sessionId) ?: return null
        val control = registry.session(conferenceId) ?: return null
        if (control.channelId != channelId) return null
        if (!adapter.rosterEpochMatches(control, rosterEpoch)) return null
        return adapter.toSessionFact(control).copy(sessionId = sessionId)
    }

    override fun memberBinding(
        sessionId: String,
        moduleId: String,
    ): MemberBindingFact? {
        val conferenceId = resolveConferenceId(sessionId) ?: return null
        val session = registry.session(conferenceId) ?: return null
        val member = registry.member(conferenceId, moduleId) ?: return null
        if (!adapter.memberMatchesSession(session, member)) return null
        return adapter.toMemberBinding(member)
    }

    override fun memberReplaceBinding(
        sessionId: String,
        moduleId: String,
    ): Pair<MemberBindingFact, MemberBindingFact>? {
        val conferenceId = resolveConferenceId(sessionId) ?: return null
        val session = registry.session(conferenceId) ?: return null
        val pair = registry.memberReplacePair(conferenceId, moduleId) ?: return null
        if (!adapter.memberMatchesSession(session, pair.second)) return null
        val oldBinding = adapter.toMemberBinding(pair.first)
        val newBinding = adapter.toMemberBinding(pair.second)
        registry.consumeMemberReplace(conferenceId, moduleId)
        return Pair(oldBinding, newBinding)
    }

    override fun memberReplacePending(
        sessionId: String,
        moduleId: String,
    ): Boolean {
        val conferenceId = resolveConferenceId(sessionId) ?: return false
        return registry.memberReplacePair(conferenceId, moduleId) != null
    }

    private fun resolveConferenceId(sessionId: String): String? =
        index.conferenceIdForSession(sessionId) ?: sessionId.takeIf { registry.session(it) != null }
}
