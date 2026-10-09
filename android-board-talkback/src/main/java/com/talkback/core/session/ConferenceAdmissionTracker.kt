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
    private val admissionHandoffActive = linkedMapOf<ConferenceAdmissionKey, Boolean>()

    fun phase(key: ConferenceAdmissionKey): ConferenceAdmissionPhase? = phases[key]

    fun allowsRecovery(key: ConferenceAdmissionKey): Boolean =
        phases[key] == ConferenceAdmissionPhase.READY

    fun isAdmissionHandoffActive(key: ConferenceAdmissionKey): Boolean =
        admissionHandoffActive[key] == true

    /**
     * Invite resend / ICE restart / recovery reattach must not change offer lineage
     * while ACCEPT→ICE_CONNECTED is in progress.
     */
    fun canRestartConferenceEdge(key: ConferenceAdmissionKey): Boolean =
        allowsRecovery(key) && !isAdmissionHandoffActive(key)

    fun beginAdmissionHandoff(key: ConferenceAdmissionKey) {
        if (admissionHandoffActive[key] == true) return
        admissionHandoffActive[key] = true
        logSink(
            "CONFERENCE_ADMISSION_HANDOFF session=${key.sessionId} peer=${key.peerId} " +
                "active=true scope=CONFERENCE"
        )
    }

    fun completeAdmissionHandoff(key: ConferenceAdmissionKey) {
        if (admissionHandoffActive[key] != true) return
        admissionHandoffActive[key] = false
        logSink(
            "CONFERENCE_ADMISSION_HANDOFF session=${key.sessionId} peer=${key.peerId} " +
                "active=false scope=CONFERENCE"
        )
    }

    /**
     * ADR-0052: symmetric offerer/Answerer initial-admission READY commit.
     * Idempotent — does not emit a duplicate transition when already READY.
     */
    fun markReadyIfAbsent(
        key: ConferenceAdmissionKey,
        reason: ConferenceAdmissionTransitionReason = ConferenceAdmissionTransitionReason.ANSWER_COMMITTED
    ) {
        if (phases[key] == ConferenceAdmissionPhase.READY) return
        transition(key, ConferenceAdmissionPhase.READY, reason)
    }

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
            admissionHandoffActive.remove(key)
            transition(key, ConferenceAdmissionPhase.TERMINATED, reason)
        }
    }

    fun removeSession(sessionId: String) {
        phases.keys.removeIf { it.sessionId == sessionId }
        admissionHandoffActive.keys.removeIf { it.sessionId == sessionId }
    }

    internal fun resetForTest() {
        phases.clear()
        admissionHandoffActive.clear()
    }
}
