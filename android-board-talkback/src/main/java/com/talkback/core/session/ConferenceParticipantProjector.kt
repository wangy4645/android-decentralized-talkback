package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.ModuleId

/**
 * Read-only Conference participant projections (R32/R44). No side effects.
 *
 * Separates roster facts from UI-visible participant presence.
 */
object ConferenceParticipantProjector {

    data class Input(
        val localModuleId: ModuleId,
        val localKey: String,
        val sessionAccepted: Boolean,
        val roster: List<EndpointAddress>,
        val memberViews: List<MemberView>,
        val leftModuleIds: Set<String> = emptySet()
    )

    data class Output(
        /** Full roster member views (control plane / debug). Mirrors [Input.memberViews]. */
        val rosterParticipants: List<MemberView>,
        val visibleParticipants: List<ConferenceParticipantViewState>,
        val visibleParticipantCount: Int,
        /** Membership-joined count for primary meeting size (ADR-0020 P2); pending invitees excluded. */
        val joinedParticipantCount: Int,
        val pendingInviteeCount: Int,
        val awaitingAdditionalParticipants: Boolean
    )

    fun project(input: Input): Output {
        val rosterParticipants = input.memberViews
        val visible = buildVisibleParticipants(input)
        val awaiting = input.sessionAccepted && hasAwaitingInvitees(input)
        return Output(
            rosterParticipants = rosterParticipants,
            visibleParticipants = visible,
            visibleParticipantCount = visible.count { it.countsTowardParticipantTotal },
            joinedParticipantCount = countJoinedParticipants(input),
            pendingInviteeCount = countPendingInvitees(input),
            awaitingAdditionalParticipants = awaiting
        )
    }

    private fun countJoinedParticipants(input: Input): Int {
        if (!input.sessionAccepted) return 0
        val joinedRemotes = input.memberViews.count { view ->
            view.moduleId != input.localModuleId.value &&
                view.moduleId !in input.leftModuleIds &&
                view.invite == InviteState.ACCEPTED
        }
        return 1 + joinedRemotes
    }

    private fun countPendingInvitees(input: Input): Int =
        input.memberViews.count { view ->
            view.moduleId != input.localModuleId.value &&
                view.moduleId !in input.leftModuleIds &&
                (view.invite == InviteState.INVITING || view.invite == InviteState.RINGING)
        }

    private fun buildVisibleParticipants(input: Input): List<ConferenceParticipantViewState> {
        if (!input.sessionAccepted) return emptyList()
        val result = mutableListOf<ConferenceParticipantViewState>()
        result += ConferenceParticipantViewState(
            key = input.localKey,
            moduleId = input.localModuleId.value,
            displayState = ConferenceParticipantDisplayState.VISIBLE_LOCAL,
            isLocal = true
        )
        val viewsByModule = input.memberViews.associateBy { it.moduleId }
        input.roster
            .asSequence()
            .map { it.moduleId.value }
            .filter { it != input.localModuleId.value }
            .filter { it !in input.leftModuleIds }
            .distinct()
            .forEach { moduleId ->
                val view = viewsByModule[moduleId] ?: return@forEach
                val displayState = displayStateForRemote(view, moduleId in input.leftModuleIds) ?: return@forEach
                result += ConferenceParticipantViewState(
                    key = view.key,
                    moduleId = moduleId,
                    displayState = displayState,
                    isLocal = false
                )
            }
        return result
    }

    internal fun displayStateForRemote(view: MemberView, left: Boolean): ConferenceParticipantDisplayState? {
        if (left) return null
        if (view.invite != InviteState.ACCEPTED) return null
        if (view.media == MediaState.NONE) return null
        return when (view.media) {
            MediaState.CONNECTING -> ConferenceParticipantDisplayState.VISIBLE_CONNECTING
            MediaState.CONNECTED -> ConferenceParticipantDisplayState.VISIBLE_CONNECTED
            MediaState.RECONNECTING -> ConferenceParticipantDisplayState.VISIBLE_RECONNECTING
            MediaState.FAILED -> ConferenceParticipantDisplayState.VISIBLE_FAILED
            MediaState.NONE -> null
        }
    }

    private fun hasAwaitingInvitees(input: Input): Boolean =
        input.memberViews.any { view ->
            view.moduleId != input.localModuleId.value &&
                view.moduleId !in input.leftModuleIds &&
                (
                    view.invite == InviteState.INVITING ||
                        view.invite == InviteState.RINGING ||
                        (view.invite == InviteState.ACCEPTED && view.media == MediaState.NONE)
                    )
        }

    data class MemberVisibilityExplanation(
        val moduleId: String,
        val visible: Boolean,
        val reason: String,
        val invite: InviteState,
        val media: MediaState
    )

    /** Forensics helper for [ParticipantLifecycleTracer]; mirrors [displayStateForRemote] rules. */
    fun explainMemberVisibility(input: Input): List<MemberVisibilityExplanation> {
        val viewsByModule = input.memberViews.associateBy { it.moduleId }
        val remoteModuleIds = input.roster
            .asSequence()
            .map { it.moduleId.value }
            .filter { it != input.localModuleId.value }
            .distinct()
            .toList()
        if (!input.sessionAccepted) {
            return remoteModuleIds.map { moduleId ->
                val view = viewsByModule[moduleId]
                MemberVisibilityExplanation(
                    moduleId = moduleId,
                    visible = false,
                    reason = "SESSION_NOT_ACCEPTED",
                    invite = view?.invite ?: InviteState.NONE,
                    media = view?.media ?: MediaState.NONE
                )
            }
        }
        val visibleIds = buildVisibleParticipants(input)
            .asSequence()
            .filter { !it.isLocal }
            .map { it.moduleId }
            .toSet()
        return remoteModuleIds.map { moduleId ->
            val view = viewsByModule[moduleId]
            when {
                view == null -> MemberVisibilityExplanation(
                    moduleId = moduleId,
                    visible = false,
                    reason = "MISSING_MEMBER_VIEW",
                    invite = InviteState.NONE,
                    media = MediaState.NONE
                )
                moduleId in input.leftModuleIds -> MemberVisibilityExplanation(
                    moduleId = moduleId,
                    visible = false,
                    reason = "LEFT",
                    invite = view.invite,
                    media = view.media
                )
                moduleId in visibleIds -> MemberVisibilityExplanation(
                    moduleId = moduleId,
                    visible = true,
                    reason = "VISIBLE",
                    invite = view.invite,
                    media = view.media
                )
                else -> MemberVisibilityExplanation(
                    moduleId = moduleId,
                    visible = false,
                    reason = exclusionReason(view),
                    invite = view.invite,
                    media = view.media
                )
            }
        }
    }

    internal fun exclusionReason(view: MemberView): String = when {
        view.invite == InviteState.NONE -> "INVITE_NONE"
        view.invite == InviteState.INVITING -> "INVITE_INVITING"
        view.invite == InviteState.RINGING -> "INVITE_RINGING"
        view.invite == InviteState.DECLINED -> "INVITE_DECLINED"
        view.invite == InviteState.EXPIRED -> "INVITE_EXPIRED"
        view.invite != InviteState.ACCEPTED -> "INVITE_NOT_ACCEPTED"
        view.media == MediaState.NONE -> "MEDIA_NONE"
        else -> "FILTERED"
    }
}
