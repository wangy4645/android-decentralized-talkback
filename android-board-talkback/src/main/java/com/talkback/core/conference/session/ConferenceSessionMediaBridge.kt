package com.talkback.core.conference.session

/**
 * Thin coordinator delegation surface — keeps multicast session state out of [TalkbackCoordinator].
 *
 * Coordinator calls only this bridge; catalog/epoch/runtime remain in [ConferenceSessionMediaWiring].
 */
object ConferenceSessionMediaBridge {
    @Volatile
    var wiring: ConferenceSessionMediaWiring? = null

    private val generations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun startSession(fact: ConferenceSessionMediaFact): Boolean {
        val ok = wiring?.startSession(fact) == true
        if (ok) {
            generations[fact.sessionId] = fact.generation
        }
        return ok
    }

    fun installMember(
        sessionId: String,
        binding: MemberBindingFact,
    ): Boolean = wiring?.installMember(sessionId, binding) == true

    fun removeMember(
        sessionId: String,
        moduleId: String,
        incarnationId: Long,
    ): Boolean = wiring?.removeMember(sessionId, moduleId, incarnationId) == true

    fun replaceMember(
        sessionId: String,
        oldBinding: MemberBindingFact,
        newBinding: MemberBindingFact,
    ): Boolean = wiring?.replaceMember(sessionId, oldBinding, newBinding) == true

    fun beginSessionTeardown(sessionId: String): Boolean {
        val generation = generations[sessionId] ?: return false
        return wiring?.beginSessionTeardown(sessionId, generation) == true
    }

    fun stopSession(sessionId: String): Boolean {
        val generation = generations.remove(sessionId) ?: return false
        return wiring?.stopSession(sessionId, generation) == true
    }

    fun hasSession(sessionId: String): Boolean = wiring?.hasSession(sessionId) == true

    fun currentMediaKeyEpoch(sessionId: String): Long? = wiring?.currentMediaKeyEpoch(sessionId)

    fun rotateMediaKeyEpoch(
        sessionId: String,
        fact: ConferenceSessionMediaFact,
    ): Boolean {
        val ok = wiring?.rotateMediaKeyEpoch(sessionId, fact) == true
        if (ok) {
            generations[fact.sessionId] = fact.generation
        }
        return ok
    }

    fun currentIncarnation(
        sessionId: String,
        moduleId: String,
    ): Long? = wiring?.catalog(sessionId)?.get(moduleId)?.incarnationId

    fun stopSession(
        sessionId: String,
        generation: Long,
    ): Boolean {
        generations.remove(sessionId)
        return wiring?.stopSession(sessionId, generation) == true
    }
}
