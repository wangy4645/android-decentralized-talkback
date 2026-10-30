package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceIdAuthority
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps Meeting [sessionId] (UUID) ↔ Profile01 wire [conferenceId] (id128 hex).
 *
 * Registry keys facts by wire conferenceId; coordinator hooks use Meeting sessionId.
 */
class MeetingProfile01ConferenceSessionIndex {
    data class Binding(
        val sessionId: String,
        val channelId: String,
        var rosterEpoch: Long,
        var conferenceId: String? = null,
        val mediaConnectedModules: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    private val bySessionId = ConcurrentHashMap<String, Binding>()
    private val conferenceToSession = ConcurrentHashMap<String, String>()

    fun registerSession(
        sessionId: String,
        channelId: String,
        rosterEpoch: Long,
    ) {
        bySessionId[sessionId] =
            Binding(
                sessionId = sessionId,
                channelId = channelId,
                rosterEpoch = rosterEpoch,
            )
    }

    fun updateRosterEpoch(
        sessionId: String,
        rosterEpoch: Long,
    ) {
        bySessionId[sessionId]?.rosterEpoch = rosterEpoch
    }

    fun bindConferenceId(
        sessionId: String,
        conferenceId: String,
    ) {
        val binding = bySessionId[sessionId] ?: return
        binding.conferenceId = conferenceId
        conferenceToSession[conferenceId] = sessionId
    }

    fun markMediaConnected(
        sessionId: String,
        moduleId: String,
    ) {
        bySessionId[sessionId]?.mediaConnectedModules?.add(moduleId)
    }

    fun unregisterSession(sessionId: String) {
        val binding = bySessionId.remove(sessionId)
        binding?.conferenceId?.let { conferenceToSession.remove(it) }
    }

    fun bindingForSession(sessionId: String): Binding? = bySessionId[sessionId]

    fun sessionIdForConference(conferenceId: String): String? = conferenceToSession[conferenceId]

    fun conferenceIdForSession(sessionId: String): String? = bySessionId[sessionId]?.conferenceId

    /**
     * Allocate stable id128 hex on first authoritative origin read — deterministic from sessionId.
     */
    fun ensureConferenceIdHex(sessionId: String): String {
        val binding = bySessionId[sessionId] ?: error("session not registered: $sessionId")
        val existing = binding.conferenceId
        if (existing != null) return existing
        val id128 = Profile01ConferenceIdAuthority.deriveId128Hex(sessionId)
        bindConferenceId(sessionId, id128)
        return id128
    }
}
