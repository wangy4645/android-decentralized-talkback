package com.talkback.core.conference.session

/**
 * Control-plane adapter for authoritative session/member binding facts.
 *
 * Null until conference control publishes verified facts; coordinator hooks no-op without a port.
 */
interface ConferenceSessionMediaFactPort {
    fun sessionFact(
        sessionId: String,
        channelId: String,
        rosterEpoch: Long,
    ): ConferenceSessionMediaFact?

    fun memberBinding(
        sessionId: String,
        moduleId: String,
    ): MemberBindingFact?

    /**
     * Rejoin / successor path — old and new incarnation bindings from control plane.
     */
    fun memberReplaceBinding(
        sessionId: String,
        moduleId: String,
    ): Pair<MemberBindingFact, MemberBindingFact>?

    /** True when control plane has an unconsumed G-old → G-new replace obligation. */
    fun memberReplacePending(
        sessionId: String,
        moduleId: String,
    ): Boolean
}
