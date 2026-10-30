package com.talkback.core.conference.session.gbc

import com.talkback.core.conference.session.ConferenceMediaMemberControlFact
import com.talkback.core.conference.session.ConferenceMediaSessionControlFact
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ControlFactPublishOutcome

/**
 * GBC / control-plane → [ConferenceSessionMediaControlFactRegistry] publisher bridge.
 *
 * Publishes only after membership/source declaration has authoritatively converged.
 * Does not own lifecycle policy, replace decisions, or media runtime state.
 */
class ConferenceSessionMediaGbcPublisherBridge(
    private val registry: ConferenceSessionMediaControlFactRegistry,
) {
    fun publishedSession(conferenceId: String): com.talkback.core.conference.session.ConferenceMediaSessionControlFact? =
        registry.session(conferenceId)

    fun publishSessionDeclaration(
        declaration: AuthoritativeConferenceMediaSessionDeclaration,
    ): ControlFactPublishOutcome {
        if (declaration.disposition != ConferenceMediaDeclarationDisposition.AUTHORITATIVE) {
            return when (declaration.disposition) {
                ConferenceMediaDeclarationDisposition.SUPERSEDED ->
                    ControlFactPublishOutcome.REJECTED_SUPERSEDED
                ConferenceMediaDeclarationDisposition.PENDING ->
                    ControlFactPublishOutcome.REJECTED_NOT_AUTHORITATIVE
                ConferenceMediaDeclarationDisposition.AUTHORITATIVE ->
                    ControlFactPublishOutcome.ACCEPTED
            }
        }
        if (!validateSessionComplete(declaration)) {
            return ControlFactPublishOutcome.REJECTED_INCOMPLETE
        }
        return registry.publishSession(toControlFact(declaration))
    }

    fun publishMemberDeclaration(
        declaration: AuthoritativeConferenceMediaMemberDeclaration,
    ): ControlFactPublishOutcome {
        if (declaration.disposition != ConferenceMediaDeclarationDisposition.AUTHORITATIVE) {
            return when (declaration.disposition) {
                ConferenceMediaDeclarationDisposition.SUPERSEDED ->
                    ControlFactPublishOutcome.REJECTED_SUPERSEDED
                ConferenceMediaDeclarationDisposition.PENDING ->
                    ControlFactPublishOutcome.REJECTED_NOT_AUTHORITATIVE
                ConferenceMediaDeclarationDisposition.AUTHORITATIVE ->
                    ControlFactPublishOutcome.ACCEPTED
            }
        }
        if (!validateMemberComplete(declaration)) {
            return ControlFactPublishOutcome.REJECTED_INCOMPLETE
        }
        return registry.publishMember(toControlFact(declaration))
    }

    fun withdrawSession(conferenceId: String) {
        registry.clearSession(conferenceId)
    }

    private fun toControlFact(
        declaration: AuthoritativeConferenceMediaSessionDeclaration,
    ): ConferenceMediaSessionControlFact =
        ConferenceMediaSessionControlFact(
            conferenceId = declaration.conferenceId,
            channelId = declaration.channelId,
            conferenceEpoch = declaration.conferenceEpoch,
            membershipVersion = declaration.membershipVersion,
            mediaKeyEpoch = declaration.mediaKeyEpoch,
            endpoint = declaration.endpoint,
            networkInterfaceName = declaration.networkInterfaceName,
            masterKey = declaration.masterKey.copyOf(),
            masterSalt = declaration.masterSalt.copyOf(),
            keyContextHint64 = declaration.keyContextHint64.copyOf(),
            factGeneration = declaration.factGeneration,
            superseded = false,
        )

    private fun toControlFact(
        declaration: AuthoritativeConferenceMediaMemberDeclaration,
    ): ConferenceMediaMemberControlFact =
        ConferenceMediaMemberControlFact(
            conferenceId = declaration.conferenceId,
            moduleId = declaration.moduleId,
            membershipIncarnationId = declaration.membershipIncarnationId,
            ssrc = declaration.ssrc,
            sourceAdmissionKey48 = declaration.sourceAdmissionKey48.copyOf(),
            mediaKeyEpoch = declaration.mediaKeyEpoch,
            membershipVersion = declaration.membershipVersion,
            factGeneration = declaration.factGeneration,
            superseded = false,
        )

    companion object {
        fun validateSessionComplete(declaration: AuthoritativeConferenceMediaSessionDeclaration): Boolean =
            declaration.conferenceId.isNotBlank() &&
                declaration.channelId.isNotBlank() &&
                declaration.conferenceEpoch > 0L &&
                declaration.membershipVersion >= 0L &&
                declaration.mediaKeyEpoch > 0L &&
                declaration.factGeneration > 0L &&
                declaration.networkInterfaceName.isNotBlank() &&
                declaration.masterKey.size == MASTER_KEY_BYTES &&
                declaration.masterSalt.size == MASTER_SALT_BYTES &&
                declaration.keyContextHint64.size == KEY_CONTEXT_HINT_BYTES

        fun validateMemberComplete(declaration: AuthoritativeConferenceMediaMemberDeclaration): Boolean =
            declaration.conferenceId.isNotBlank() &&
                declaration.moduleId.isNotBlank() &&
                declaration.membershipIncarnationId > 0L &&
                declaration.factGeneration > 0L &&
                declaration.mediaKeyEpoch > 0L &&
                declaration.membershipVersion >= 0L &&
                declaration.sourceAdmissionKey48.size == SOURCE_ADMISSION_KEY_BYTES

        private const val SOURCE_ADMISSION_KEY_BYTES = 6
        private const val MASTER_KEY_BYTES = 16
        private const val MASTER_SALT_BYTES = 12
        private const val KEY_CONTEXT_HINT_BYTES = 8
    }
}
