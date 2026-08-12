package com.talkback.core.session

/**
 * ADR-0052 PR-C1: per (sessionId, peerId) conference admission phase projection.
 * No recovery, ICE, or signaling logic — phase memory + observability only.
 */
class ConferenceAdmissionTracker(
    private val logSink: (String) -> Unit = { message ->
        android.util.Log.i("Talkback", message)
    }
) {
    private val phases = linkedMapOf<ConferenceAdmissionKey, ConferenceAdmissionPhase>()

    fun phase(key: ConferenceAdmissionKey): ConferenceAdmissionPhase? = phases[key]

    fun allowsRecovery(key: ConferenceAdmissionKey): Boolean =
        phases[key] == ConferenceAdmissionPhase.READY

    fun transition(
        key: ConferenceAdmissionKey,
        phase: ConferenceAdmissionPhase,
        reason: ConferenceAdmissionTransitionReason
    ) {
        phases[key] = phase
        logSink(
            "CONFERENCE_ADMISSION_PHASE session=${key.sessionId} peer=${key.peerId} " +
                "phase=$phase reason=$reason scope=CONFERENCE"
        )
    }

    fun terminateSession(
        sessionId: String,
        reason: ConferenceAdmissionTransitionReason = ConferenceAdmissionTransitionReason.SESSION_TERMINATED
    ) {
        phases.keys.filter { it.sessionId == sessionId }.forEach { key ->
            transition(key, ConferenceAdmissionPhase.TERMINATED, reason)
        }
    }

    fun removeSession(sessionId: String) {
        phases.keys.removeIf { it.sessionId == sessionId }
    }

    internal fun resetForTest() {
        phases.clear()
    }
}
