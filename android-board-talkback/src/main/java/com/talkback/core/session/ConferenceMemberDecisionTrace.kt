package com.talkback.core.session

import com.talkback.core.util.TalkbackLog

/**
 * Forensics for conference membership / leave decisions (ADR-0023 boundary).
 * Observe only - never enforce.
 *
 * Grep: `CONFERENCE_LIFECYCLE_EVENT`, `GROUP_LEAVE_SENT`, `RECOVERY_TRANSITION`
 */
object ConferenceMemberDecisionTrace {

    private const val STACK_SKIP = 4
    private const val STACK_FRAMES = 8

    private fun triggerStack(): String =
        Thread.currentThread().stackTrace
            .drop(STACK_SKIP)
            .take(STACK_FRAMES)
            .joinToString(" <- ") { "${it.fileName}:${it.lineNumber}#${it.methodName}" }

    fun localLeaveRequest(
        sessionId: String,
        participant: String,
        caller: String,
        reason: String,
        conferenceState: String,
        recoverySummary: String,
        pendingActions: List<String>,
        rosterEpoch: Long,
        isHost: Boolean
    ) {
        TalkbackLog.i(
            "CONFERENCE_LIFECYCLE_EVENT session=$sessionId event=LOCAL_LEAVE_REQUEST " +
                "participant=$participant caller=$caller reason=$reason host=$isHost " +
                "conference=$conferenceState recovery=[$recoverySummary] " +
                "pendingActions=[${pendingActions.joinToString(",")}] rosterEpoch=$rosterEpoch " +
                "triggerStack=${triggerStack()}"
        )
    }

    fun groupLeaveSent(
        sessionId: String,
        fromModule: String,
        caller: String,
        reason: String,
        targetCount: Int,
        conferenceState: String,
        recoverySummary: String,
        pendingActions: List<String>,
        rosterEpoch: Long
    ) {
        TalkbackLog.i(
            "GROUP_LEAVE_SENT session=$sessionId from=$fromModule caller=$caller reason=$reason " +
                "targets=$targetCount conference=$conferenceState recovery=[$recoverySummary] " +
                "pendingActions=[${pendingActions.joinToString(",")}] rosterEpoch=$rosterEpoch " +
                "triggerStack=${triggerStack()}"
        )
    }

    fun groupLeaveReceived(
        sessionId: String,
        fromModule: String,
        localModule: String,
        isHostLeave: Boolean,
        conferenceState: String,
        recoverySummary: String,
        pendingActions: List<String>,
        rosterEpoch: Long,
        signalTsMs: Long
    ) {
        TalkbackLog.i(
            "CONFERENCE_LIFECYCLE_EVENT session=$sessionId event=GROUP_LEAVE_RECEIVED " +
                "from=$fromModule local=$localModule hostLeave=$isHostLeave signalTsMs=$signalTsMs " +
                "conference=$conferenceState recovery=[$recoverySummary] " +
                "pendingActions=[${pendingActions.joinToString(",")}] rosterEpoch=$rosterEpoch"
        )
    }

    fun authorityMemberDecision(
        sessionId: String,
        participant: String,
        decision: String,
        source: AuthorityMembershipMutationSource,
        oldMembership: String,
        conferenceState: String,
        recoverySummary: String,
        pendingActions: List<String>,
        rosterEpoch: Long,
        remaining: Int
    ) {
        TalkbackLog.i(
            "AUTHORITY_MEMBER_DECISION session=$sessionId participant=$participant " +
                "decision=$decision source=$source oldMembership=$oldMembership " +
                "conference=$conferenceState recovery=[$recoverySummary] " +
                "pendingActions=[${pendingActions.joinToString(",")}] " +
                "rosterEpoch=$rosterEpoch remaining=$remaining"
        )
    }

    fun recoveryTransition(
        sessionId: String,
        remoteModuleId: String,
        oldPhase: EdgeRecoveryPhase?,
        newPhase: EdgeRecoveryPhase,
        trigger: String,
        attempt: Long,
        obligationOpen: Boolean,
        pendingCompletion: Boolean
    ) {
        TalkbackLog.i(
            "RECOVERY_TRANSITION session=$sessionId remote=$remoteModuleId " +
                "old=${oldPhase ?: "NONE"} new=$newPhase trigger=$trigger attempt=$attempt " +
                "obligationOpen=$obligationOpen pendingCompletion=$pendingCompletion"
        )
    }

    fun recoveryAttemptOpened(
        sessionId: String,
        remoteModuleId: String,
        newAttempt: Long,
        previousAttempt: Long?,
        previousPhase: EdgeRecoveryPhase?,
        obligationOpen: Boolean,
        trigger: String,
        pathway: String
    ) {
        TalkbackLog.i(
            "RECOVERY_ATTEMPT_OPENED session=$sessionId remote=$remoteModuleId " +
                "newAttempt=$newAttempt previousAttempt=${previousAttempt ?: "NONE"} " +
                "previousPhase=${previousPhase ?: "NONE"} previousObligationOpen=$obligationOpen " +
                "trigger=$trigger pathway=$pathway"
        )
    }
    fun recoverySummaryForSession(
        controller: ConferenceEdgeRecoveryController,
        sessionId: String
    ): String {
        val facts = controller.factsForSession(sessionId)
        val edges = controller.edgePhaseSummary(sessionId)
        return "anyRecovering=${facts.anyRecovering} recovering=${facts.recoveringRemoteModuleIds.sorted()}" +
            " edges={$edges}"
    }
}
