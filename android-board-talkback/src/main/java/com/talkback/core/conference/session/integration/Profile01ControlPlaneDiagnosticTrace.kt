package com.talkback.core.conference.session.integration

import java.util.concurrent.ConcurrentHashMap

/**
 * Field RCA — control-plane transition trace (membership ingress → materialize → replay → shadow fence).
 * Record-only; must not alter convergence, publish targets, or replay policy.
 */
object Profile01ControlPlaneDiagnosticTrace {
    private data class SessionTrace(
        @Volatile var conferenceIdHex: String? = null,
        @Volatile var channelId: String? = null,
        @Volatile var lastMembershipIngress: String? = null,
        @Volatile var lastMaterializeOutcome: String? = null,
        @Volatile var lastPeerReplayOutcome: String? = null,
        @Volatile var headMembershipVersion: Long? = null,
        @Volatile var headMediaKeyEpoch: Long? = null,
    )

    private val bySession = ConcurrentHashMap<String, SessionTrace>()

    fun recordMembershipIngress(
        sessionId: String,
        conferenceIdHex: String?,
        channelId: String?,
        membershipVersion: Long?,
        mediaKeyEpoch: Long?,
        phase: String,
        detail: String? = null,
    ) {
        val trace = bySession.computeIfAbsent(sessionId) { SessionTrace() }
        if (conferenceIdHex != null) trace.conferenceIdHex = conferenceIdHex
        if (channelId != null) trace.channelId = channelId
        if (membershipVersion != null) trace.headMembershipVersion = membershipVersion
        if (mediaKeyEpoch != null) trace.headMediaKeyEpoch = mediaKeyEpoch
        trace.lastMembershipIngress = phase + detail?.let { ":$it" }.orEmpty()
        log(
            "PROFILE01_CONTROL_PLANE_DIAG phase=MEMBERSHIP_INGRESS session=$sessionId " +
                "conferenceIdHex=${conferenceIdHex ?: "-"} channelId=${channelId ?: "-"} " +
                "membershipVersion=${membershipVersion ?: "-"} mediaKeyEpoch=${mediaKeyEpoch ?: "-"} " +
                "outcome=$phase detail=${detail ?: "-"}",
        )
    }

    fun recordMaterializeHead(
        sessionId: String,
        conferenceIdHex: String,
        channelId: String,
        outcome: String,
    ) {
        val trace = bySession.computeIfAbsent(sessionId) { SessionTrace() }
        trace.conferenceIdHex = conferenceIdHex
        trace.channelId = channelId
        trace.lastMaterializeOutcome = outcome
        log(
            "PROFILE01_CONTROL_PLANE_DIAG phase=MATERIALIZE_HEAD session=$sessionId " +
                "conferenceIdHex=$conferenceIdHex channelId=$channelId outcome=$outcome",
        )
    }

    fun recordPeerReplay(
        sessionId: String,
        conferenceIdHex: String?,
        outcome: String,
    ) {
        val trace = bySession.computeIfAbsent(sessionId) { SessionTrace() }
        if (conferenceIdHex != null) trace.conferenceIdHex = conferenceIdHex
        trace.lastPeerReplayOutcome = outcome
        log(
            "PROFILE01_CONTROL_PLANE_DIAG phase=PEER_REPLAY session=$sessionId " +
                "conferenceIdHex=${conferenceIdHex ?: trace.conferenceIdHex ?: "-"} outcome=$outcome",
        )
    }

    enum class MembershipOriginPeerDisposition {
        SENT,
        SKIPPED_ALREADY_PUBLISHED,
        SEND_FAILED,
    }

    fun recordMembershipOriginPeer(
        sessionId: String,
        conferenceIdHex: String,
        channelId: String,
        mediaKeyEpoch: Long,
        peerModuleId: String,
        disposition: MembershipOriginPeerDisposition,
    ) {
        log(
            "PROFILE01_CONTROL_PLANE_DIAG phase=MEMBERSHIP_ORIGIN_PEER session=$sessionId " +
                "conferenceIdHex=$conferenceIdHex channelId=$channelId mediaKeyEpoch=$mediaKeyEpoch " +
                "peer=$peerModuleId disposition=$disposition",
        )
    }

    fun recordSupplementDrainSkip(
        sessionId: String,
        reason: String,
        detail: String? = null,
    ) {
        log(
            "PROFILE01_CONTROL_PLANE_DIAG phase=SUPPLEMENT_DRAIN session=$sessionId " +
                "outcome=SKIPPED reason=$reason detail=${detail ?: "-"}",
        )
    }

    fun recordSupplementPeerEvaluation(
        sessionId: String,
        peerModuleId: String,
        evaluation: String,
        detail: String? = null,
    ) {
        log(
            "PROFILE01_CONTROL_PLANE_DIAG phase=SUPPLEMENT_PEER session=$sessionId peer=$peerModuleId " +
                "evaluation=$evaluation detail=${detail ?: "-"}",
        )
    }

    fun logShadowTxNoSessionFact(
        sessionId: String,
        conferenceIdHex: String,
        registrySessionPresent: Boolean,
    ) {
        val trace = bySession[sessionId]
        log(
            "SHADOW_TX_FENCED session=$sessionId reason=NO_SESSION_FACT " +
                "conferenceIdHex=$conferenceIdHex channelId=${trace?.channelId ?: "-"} " +
                "registrySessionPresent=$registrySessionPresent " +
                "headMembershipVersion=${trace?.headMembershipVersion ?: "-"} " +
                "headMediaKeyEpoch=${trace?.headMediaKeyEpoch ?: "-"} " +
                "lastMembershipIngress=${trace?.lastMembershipIngress ?: "-"} " +
                "lastMaterializeOutcome=${trace?.lastMaterializeOutcome ?: "-"} " +
                "lastPeerReplayOutcome=${trace?.lastPeerReplayOutcome ?: "-"}",
        )
    }

    internal fun snapshotForSession(sessionId: String): Map<String, String?> {
        val trace = bySession[sessionId] ?: return emptyMap()
        return mapOf(
            "conferenceIdHex" to trace.conferenceIdHex,
            "channelId" to trace.channelId,
            "lastMembershipIngress" to trace.lastMembershipIngress,
            "lastMaterializeOutcome" to trace.lastMaterializeOutcome,
            "lastPeerReplayOutcome" to trace.lastPeerReplayOutcome,
            "headMembershipVersion" to trace.headMembershipVersion?.toString(),
            "headMediaKeyEpoch" to trace.headMediaKeyEpoch?.toString(),
        )
    }

    internal fun clearForTest() {
        bySession.clear()
    }

    private fun log(message: String) {
        try {
            android.util.Log.i(MeetingProductMediaShadow.LOG_TAG, message)
        } catch (_: Throwable) {
        }
    }
}
