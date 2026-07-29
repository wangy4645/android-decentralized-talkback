package com.talkback.core.util

import com.talkback.core.session.AuthorityMembershipMutationSource
import com.talkback.core.session.ConferenceEdgeRecoveryController
import com.talkback.core.session.EdgeRecoveryPhase
import com.talkback.core.session.TalkbackSession
import com.talkback.core.session.SessionType

/**
 * P2-A prep observation: conference recovery ownership / membership causality (ADR-0022).
 *
 * Read-only telemetry. Does not gate recovery, membership, rejoin, or prune.
 *
 * Grep: `CONFERENCE_RECOVERY_OWNERSHIP_SNAPSHOT`, `CONFERENCE_REJOIN_RESPONSE`
 */
object ConferenceRecoveryOwnershipLog {

  enum class AttemptObservationState {
    OPEN,
    WAIT_ROUTE,
    WAIT_ICE,
    FAILED,
    SUPERSEDED,
    RECOVERED
  }

  enum class MembershipObservationState {
    JOINED,
    LEAVING,
    REMOVED,
    UNKNOWN
  }

  enum class MembershipMutationDecisionType {
    PRUNE,
    REJOIN_REQUIRED,
    KEEP
  }

  data class AttemptLineageObservation(
    val attemptId: Long,
    val attemptStartedAtMs: Long,
    val attemptState: AttemptObservationState,
    val phase: EdgeRecoveryPhase,
    val mediaRestored: Boolean,
    val obligationOpen: Boolean,
    val pendingCompletion: Boolean,
    val supersededFromAttempt: Long? = null
  )

  data class ParticipantMembershipObservation(
    val participantId: String,
    val observedMembershipState: MembershipObservationState,
    val observedAtMs: Long,
    val observedRosterEpoch: Long,
    val observedRosterOwner: String
  )

  data class MembershipMutationDecisionSnapshot(
    val type: MembershipMutationDecisionType,
    val reason: String,
    val sourceAttemptId: Long?,
    val decisionAtMs: Long
  )

  private var logSink: ((String) -> Unit)? = null

  internal fun resetForTest(sink: ((String) -> Unit)? = null) {
    logSink = sink
  }

  private fun log(message: String) {
    val sink = logSink
    if (sink != null) {
      sink(message)
    } else {
      TalkbackLog.i(message)
    }
  }

  fun mapPhaseToAttemptState(phase: EdgeRecoveryPhase): AttemptObservationState =
    when (phase) {
      EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING,
      EdgeRecoveryPhase.RECOVERY_PENDING -> AttemptObservationState.OPEN
      EdgeRecoveryPhase.REATTACH_REQUESTED -> AttemptObservationState.WAIT_ROUTE
      EdgeRecoveryPhase.REATTACH_ACCEPTED,
      EdgeRecoveryPhase.ICE_RESTARTING -> AttemptObservationState.WAIT_ICE
      EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY,
      EdgeRecoveryPhase.FAILED_IDENTITY_MISMATCH,
      EdgeRecoveryPhase.FAILED_STALE_LINEAGE,
      EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION -> AttemptObservationState.FAILED
      EdgeRecoveryPhase.RECOVERED -> AttemptObservationState.RECOVERED
      EdgeRecoveryPhase.CONNECTED,
      EdgeRecoveryPhase.CANCELLED -> AttemptObservationState.OPEN
    }

  fun attemptLineageFromController(
    controller: ConferenceEdgeRecoveryController,
    sessionId: String,
    participantId: String,
    supersededFromAttempt: Long? = null
  ): AttemptLineageObservation? {
    val raw = controller.attemptLineageObservation(sessionId, participantId) ?: return null
    val attemptState = when {
      supersededFromAttempt != null -> AttemptObservationState.SUPERSEDED
      else -> mapPhaseToAttemptState(raw.phase)
    }
    return AttemptLineageObservation(
      attemptId = raw.attemptId,
      attemptStartedAtMs = raw.attemptStartedAtMs,
      attemptState = attemptState,
      phase = raw.phase,
      mediaRestored = raw.mediaRestored,
      obligationOpen = raw.obligationOpen,
      pendingCompletion = raw.pendingCompletion,
      supersededFromAttempt = supersededFromAttempt
    )
  }

  fun observeParticipantMembership(
    session: TalkbackSession,
    participantId: String,
    rosterOwner: String,
    leftMemberModuleIds: Set<String> = emptySet()
  ): ParticipantMembershipObservation {
    val state = when {
      participantId in leftMemberModuleIds -> MembershipObservationState.LEAVING
      session.memberModules.any { it.value == participantId } -> MembershipObservationState.JOINED
      session.type == SessionType.CONFERENCE && session.accepted ->
        MembershipObservationState.REMOVED
      else -> MembershipObservationState.UNKNOWN
    }
    return ParticipantMembershipObservation(
      participantId = participantId,
      observedMembershipState = state,
      observedAtMs = System.currentTimeMillis(),
      observedRosterEpoch = session.rosterEpoch,
      observedRosterOwner = rosterOwner
    )
  }

  fun emitSnapshot(
    reason: String,
    conferenceId: String,
    localModuleId: String,
    participantId: String,
    attemptLineage: AttemptLineageObservation?,
    membershipObservation: ParticipantMembershipObservation,
    membershipMutationDecision: MembershipMutationDecisionSnapshot? = null
  ) {
    log(
      buildString {
        append("CONFERENCE_RECOVERY_OWNERSHIP_SNAPSHOT")
        append(" reason=").append(reason)
        append(" conferenceId=").append(conferenceId)
        append(" localModuleId=").append(localModuleId)
        append(" participantId=").append(participantId)
        if (attemptLineage != null) {
          append(" attemptId=").append(attemptLineage.attemptId)
          append(" attemptStartedAtMs=").append(attemptLineage.attemptStartedAtMs)
          append(" attemptState=").append(attemptLineage.attemptState)
          append(" attemptPhase=").append(attemptLineage.phase)
          append(" mediaRestored=").append(attemptLineage.mediaRestored)
          append(" obligationOpen=").append(attemptLineage.obligationOpen)
          append(" pendingCompletion=").append(attemptLineage.pendingCompletion)
          attemptLineage.supersededFromAttempt?.let { append(" supersededFromAttempt=").append(it) }
        } else {
          append(" attemptId=none")
        }
        append(" observedMembershipState=").append(membershipObservation.observedMembershipState)
        append(" observedAtMs=").append(membershipObservation.observedAtMs)
        append(" observedRosterEpoch=").append(membershipObservation.observedRosterEpoch)
        append(" observedRosterOwner=").append(membershipObservation.observedRosterOwner)
        if (membershipMutationDecision != null) {
          append(" decisionType=").append(membershipMutationDecision.type)
          append(" decisionReason=").append(membershipMutationDecision.reason)
          append(" sourceAttemptId=").append(membershipMutationDecision.sourceAttemptId ?: "none")
          append(" decisionAtMs=").append(membershipMutationDecision.decisionAtMs)
        }
      }
    )
  }

  fun emitFromSession(
    reason: String,
    session: TalkbackSession,
    localModuleId: String,
    participantId: String,
    controller: ConferenceEdgeRecoveryController,
    leftMemberModuleIds: Set<String> = emptySet(),
    supersededFromAttempt: Long? = null,
    membershipMutationDecision: MembershipMutationDecisionSnapshot? = null
  ) {
    val rosterOwner = session.initiatorModuleId?.value ?: localModuleId
    emitSnapshot(
      reason = reason,
      conferenceId = session.id,
      localModuleId = localModuleId,
      participantId = participantId,
      attemptLineage = attemptLineageFromController(
        controller,
        session.id,
        participantId,
        supersededFromAttempt
      ),
      membershipObservation = observeParticipantMembership(
        session,
        participantId,
        rosterOwner,
        leftMemberModuleIds
      ),
      membershipMutationDecision = membershipMutationDecision
    )
  }

  fun emitMembershipMutationDecision(
    session: TalkbackSession,
    localModuleId: String,
    participantId: String,
    controller: ConferenceEdgeRecoveryController,
    type: MembershipMutationDecisionType,
    reason: String,
    source: AuthorityMembershipMutationSource? = null
  ) {
    val attemptId = controller.attemptLineageObservation(session.id, participantId)?.attemptId
    val decisionReason = if (source != null) "${source.name}:$reason" else reason
    emitFromSession(
      reason = "membership_mutation_decision",
      session = session,
      localModuleId = localModuleId,
      participantId = participantId,
      controller = controller,
      leftMemberModuleIds = emptySet(),
      membershipMutationDecision = MembershipMutationDecisionSnapshot(
        type = type,
        reason = decisionReason,
        sourceAttemptId = attemptId,
        decisionAtMs = System.currentTimeMillis()
      )
    )
  }

  fun emitRejoinResponse(
    conferenceId: String,
    localModuleId: String,
    targetParticipantId: String,
    response: String,
    localMembershipBelief: MembershipObservationState,
    remoteMembershipHint: String,
    observedRosterEpoch: Long = 0L
  ) {
    log(
      "CONFERENCE_REJOIN_RESPONSE conferenceId=$conferenceId localModuleId=$localModuleId " +
        "target=$targetParticipantId response=$response " +
        "localMembershipBelief=$localMembershipBelief " +
        "remoteMembershipHint=$remoteMembershipHint " +
        "observedRosterEpoch=$observedRosterEpoch"
    )
  }
}
