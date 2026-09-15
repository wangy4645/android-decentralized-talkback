package com.talkback.core.conference.session

/**
 * [ConferenceSessionMediaFactPort] backed by authoritative control-plane facts.
 *
 * Reads registry + [ConferenceSessionMediaFactAdapter] only — no lifecycle policy.
 */
class ControlPlaneConferenceSessionMediaFactPort(
    private val registry: ConferenceSessionMediaControlFactRegistry,
    private val adapter: ConferenceSessionMediaFactAdapter = ConferenceSessionMediaFactAdapter,
) : ConferenceSessionMediaFactPort {
    override fun sessionFact(
        sessionId: String,
        channelId: String,
        rosterEpoch: Long,
    ): ConferenceSessionMediaFact? {
        val control = registry.session(sessionId) ?: return null
        if (control.channelId != channelId) return null
        if (!adapter.rosterEpochMatches(control, rosterEpoch)) return null
        return adapter.toSessionFact(control)
    }

    override fun memberBinding(
        sessionId: String,
        moduleId: String,
    ): MemberBindingFact? {
        val session = registry.session(sessionId) ?: return null
        val member = registry.member(sessionId, moduleId) ?: return null
        if (!adapter.memberMatchesSession(session, member)) return null
        return adapter.toMemberBinding(member)
    }

    override fun memberReplaceBinding(
        sessionId: String,
        moduleId: String,
    ): Pair<MemberBindingFact, MemberBindingFact>? {
        val session = registry.session(sessionId) ?: return null
        val pair = registry.memberReplacePair(sessionId, moduleId) ?: return null
        if (!adapter.memberMatchesSession(session, pair.second)) return null
        val oldBinding = adapter.toMemberBinding(pair.first)
        val newBinding = adapter.toMemberBinding(pair.second)
        registry.consumeMemberReplace(sessionId, moduleId)
        return Pair(oldBinding, newBinding)
    }

    override fun memberReplacePending(
        sessionId: String,
        moduleId: String,
    ): Boolean = registry.memberReplacePair(sessionId, moduleId) != null
}
