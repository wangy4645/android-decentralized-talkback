package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.gbc.AuthoritativeConferenceMediaMemberDeclaration
import com.talkback.core.conference.session.gbc.AuthoritativeConferenceMediaSessionDeclaration
import com.talkback.core.conference.session.gbc.ConferenceMediaDeclarationDisposition
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile 01 validation → authoritative declaration mapping.
 *
 * Determines disposition from verified wire facts and convergence state only.
 */
class Profile01ConferenceMediaFactValidator(
    private val trust: Profile01WireTrustBoundary,
    private val membershipConvergence: Profile01MembershipConvergenceRegistry =
        Profile01MembershipConvergenceRegistry(),
) {
    private data class SessionConvergence(
        var conferenceEpoch: Long,
        var membershipVersion: Long,
        var mediaKeyEpoch: Long,
        var sessionFactGeneration: Long,
    )

    private data class MemberConvergence(
        var sourceGeneration: Long,
        var factGeneration: Long,
    )

    private val sessions = ConcurrentHashMap<String, SessionConvergence>()
    private val members = ConcurrentHashMap<String, ConcurrentHashMap<String, MemberConvergence>>()
    private val lastSessionDeclarations =
        ConcurrentHashMap<String, AuthoritativeConferenceMediaSessionDeclaration>()

    fun membershipRegistry(): Profile01MembershipConvergenceRegistry = membershipConvergence

    fun validateSession(
        wire: Profile01WireSessionFact,
        networkInterfaceName: String,
    ): Profile01ValidationResult {
        when (val verified = trust.verifySignedFact(wire.signedFactBytes)) {
            is Profile01WireVerificationResult.Rejected ->
                return Profile01ValidationResult.Invalid(verified.reason)
            is Profile01WireVerificationResult.Verified -> {
                if (!verified.factDigest.contentEquals(wire.factDigest)) {
                    return Profile01ValidationResult.Invalid("FACT_DIGEST_MISMATCH")
                }
            }
        }
        val state = sessions[wire.conferenceId]
        if (state != null) {
            if (wire.conferenceEpoch < state.conferenceEpoch ||
                wire.membershipVersion < state.membershipVersion
            ) {
                return Profile01ValidationResult.NotPublished(
                    disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                    reason = "STALE_SESSION_GENERATION",
                )
            }
        }
        when (val seeded = membershipConvergence.seedFromCreation(wire)) {
            is Profile01MembershipApplyResult.BranchConflict ->
                return Profile01ValidationResult.Invalid(seeded.reason)
            is Profile01MembershipApplyResult.Superseded ->
                return Profile01ValidationResult.NotPublished(
                    disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                    reason = seeded.reason,
                )
            is Profile01MembershipApplyResult.ChainPending ->
                return Profile01ValidationResult.Invalid(seeded.reason)
            is Profile01MembershipApplyResult.Accepted,
            is Profile01MembershipApplyResult.Idempotent,
            -> Unit
        }
        val factGeneration = wire.conferenceEpoch * 1_000L + wire.membershipVersion
        val declaration =
            AuthoritativeConferenceMediaSessionDeclaration(
                conferenceId = wire.conferenceId,
                channelId = wire.channelId,
                conferenceEpoch = wire.conferenceEpoch,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
                endpoint = wire.endpoint,
                networkInterfaceName = networkInterfaceName,
                masterKey = wire.masterKey.copyOf(),
                masterSalt = wire.masterSalt.copyOf(),
                keyContextHint64 = wire.keyContextHint64.copyOf(),
                factGeneration = factGeneration,
                disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
            )
        sessions[wire.conferenceId] =
            SessionConvergence(
                conferenceEpoch = wire.conferenceEpoch,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
                sessionFactGeneration = factGeneration,
            )
        lastSessionDeclarations[wire.conferenceId] = declaration
        val republication =
            membershipConvergence.current(wire.conferenceId)?.let { generation ->
                republicationFor(generation)
            }
        return Profile01ValidationResult.ReadySession(declaration, republication)
    }

    fun validateMembership(wire: Profile01WireMembershipFact): Profile01ValidationResult {
        when (val verified = trust.verifySignedFact(wire.signedFactBytes)) {
            is Profile01WireVerificationResult.Rejected ->
                return Profile01ValidationResult.Invalid(verified.reason)
            is Profile01WireVerificationResult.Verified -> {
                if (!verified.factDigest.contentEquals(wire.factDigest)) {
                    return Profile01ValidationResult.Invalid("FACT_DIGEST_MISMATCH")
                }
            }
        }
        return when (val applied = membershipConvergence.applyMembership(wire)) {
            is Profile01MembershipApplyResult.Accepted -> {
                syncSessionFromGeneration(applied.generation)
                Profile01ValidationResult.ReadyMembership(
                    generation = applied.generation,
                    sessionRepublication = republicationFor(applied.generation),
                )
            }
            is Profile01MembershipApplyResult.Idempotent ->
                Profile01ValidationResult.ReadyMembership(
                    generation = applied.generation,
                    sessionRepublication = republicationFor(applied.generation),
                )
            is Profile01MembershipApplyResult.ChainPending ->
                Profile01ValidationResult.NotPublished(
                    disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                    reason = applied.reason,
                )
            is Profile01MembershipApplyResult.BranchConflict ->
                Profile01ValidationResult.Invalid(applied.reason)
            is Profile01MembershipApplyResult.Superseded ->
                Profile01ValidationResult.NotPublished(
                    disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                    reason = applied.reason,
                )
        }
    }

    fun validateMember(wire: Profile01WireMemberSourceFact): Profile01ValidationResult {
        when (val verified = trust.verifySignedFact(wire.signedFactBytes)) {
            is Profile01WireVerificationResult.Rejected ->
                return Profile01ValidationResult.Invalid(verified.reason)
            is Profile01WireVerificationResult.Verified -> {
                if (!verified.factDigest.contentEquals(wire.factDigest)) {
                    return Profile01ValidationResult.Invalid("FACT_DIGEST_MISMATCH")
                }
                val authenticatedSignerModuleId =
                    verified.authenticatedSignerModuleId
                        ?: return Profile01ValidationResult.Invalid(
                            Profile01SourceValidationReasons.MISSING_AUTHENTICATED_SIGNER,
                        )
                if (authenticatedSignerModuleId != wire.moduleId) {
                    return Profile01ValidationResult.Invalid(
                        Profile01SourceValidationReasons.SIGNER_SENDER_MISMATCH,
                    )
                }
            }
        }
        if (wire.moduleId.isBlank() || wire.sourceAdmissionKey48.size != 6) {
            return Profile01ValidationResult.Invalid("INCOMPLETE_MEMBER_BINDING")
        }
        val generation =
            membershipConvergence.current(wire.conferenceId)
                ?: return Profile01ValidationResult.Invalid("MEMBERSHIP_NOT_CONVERGED")
        if (wire.conferenceEpoch < generation.conferenceEpoch) {
            return Profile01ValidationResult.NotPublished(
                disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                reason = "STALE_MEMBER_CONTEXT",
            )
        }
        if (wire.membershipVersion != generation.membershipVersion ||
            wire.mediaKeyEpoch != generation.mediaKeyEpoch
        ) {
            return Profile01ValidationResult.NotPublished(
                disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                reason = "STALE_MEMBER_CONTEXT",
            )
        }
        if (!generation.hasMember(wire.moduleId)) {
            return Profile01ValidationResult.Invalid("MEMBER_NOT_IN_ROSTER")
        }
        val memberMap = members.getOrPut(wire.conferenceId) { ConcurrentHashMap() }
        val current = memberMap[wire.moduleId]
        if (current != null && wire.sourceGeneration < current.sourceGeneration) {
            return Profile01ValidationResult.NotPublished(
                disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                reason = "STALE_SOURCE_GENERATION",
            )
        }
        val factGeneration = wire.sourceGeneration
        val declaration =
            AuthoritativeConferenceMediaMemberDeclaration(
                conferenceId = wire.conferenceId,
                moduleId = wire.moduleId,
                membershipIncarnationId = wire.sourceGeneration,
                ssrc = wire.ssrc,
                sourceAdmissionKey48 = wire.sourceAdmissionKey48.copyOf(),
                mediaKeyEpoch = wire.mediaKeyEpoch,
                membershipVersion = wire.membershipVersion,
                factGeneration = factGeneration,
                disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
            )
        memberMap[wire.moduleId] =
            MemberConvergence(
                sourceGeneration = wire.sourceGeneration,
                factGeneration = factGeneration,
            )
        return Profile01ValidationResult.ReadyMember(declaration)
    }

    fun verifyMediaKeyPackage(
        wire: com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage,
    ): Profile01WireVerificationResult {
        when (val verified = trust.verifySignedFact(wire.signedFactBytes)) {
            is Profile01WireVerificationResult.Rejected -> return verified
            is Profile01WireVerificationResult.Verified -> {
                if (!verified.factDigest.contentEquals(wire.factDigest)) {
                    return Profile01WireVerificationResult.Rejected("FACT_DIGEST_MISMATCH")
                }
            }
        }
        val generation =
            membershipConvergence.current(wire.conferenceId.toConferenceIdHex())
                ?: return Profile01WireVerificationResult.Rejected("MEMBERSHIP_NOT_CONVERGED")
        if (wire.conferenceEpoch != generation.conferenceEpoch ||
            wire.membershipVersion != generation.membershipVersion ||
            wire.mediaKeyEpoch != generation.mediaKeyEpoch ||
            !wire.membershipFactDigest.contentEquals(generation.generationFactDigest)
        ) {
            return Profile01WireVerificationResult.Rejected("MEMBERSHIP_CONTEXT_MISMATCH")
        }
        return Profile01WireVerificationResult.Verified(wire.factDigest.copyOf())
    }

    private fun republicationFor(
        generation: Profile01MembershipGeneration,
    ): AuthoritativeConferenceMediaSessionDeclaration? {
        val prior = lastSessionDeclarations[generation.conferenceId] ?: return null
        if (prior.membershipVersion == generation.membershipVersion &&
            prior.mediaKeyEpoch == generation.mediaKeyEpoch
        ) {
            return null
        }
        // mediaKeyEpoch advance requires supplement-backed session materialization —
        // never copy stale masterKey/masterSalt across an epoch boundary.
        if (prior.mediaKeyEpoch != generation.mediaKeyEpoch) {
            return null
        }
        val updated =
            prior.copy(
                membershipVersion = generation.membershipVersion,
                factGeneration = generation.conferenceEpoch * 1_000L + generation.membershipVersion,
            )
        lastSessionDeclarations[generation.conferenceId] = updated
        return updated
    }

    /**
     * RCA4b — peer session registry bootstrap when membership head advanced past a
     * stale CREATION mediaKeyEpoch that never received its supplement.
     */
    fun bootstrapSessionDeclaration(
        declaration: AuthoritativeConferenceMediaSessionDeclaration,
    ) {
        sessions[declaration.conferenceId] =
            SessionConvergence(
                conferenceEpoch = declaration.conferenceEpoch,
                membershipVersion = declaration.membershipVersion,
                mediaKeyEpoch = declaration.mediaKeyEpoch,
                sessionFactGeneration = declaration.factGeneration,
            )
        lastSessionDeclarations[declaration.conferenceId] = declaration
    }

    fun hasSessionDeclaration(conferenceId: String): Boolean =
        lastSessionDeclarations.containsKey(conferenceId)

    private fun syncSessionFromGeneration(generation: Profile01MembershipGeneration) {
        val current = sessions[generation.conferenceId]
        if (current == null ||
            generation.membershipVersion > current.membershipVersion ||
            generation.mediaKeyEpoch > current.mediaKeyEpoch
        ) {
            sessions[generation.conferenceId] =
                SessionConvergence(
                    conferenceEpoch = generation.conferenceEpoch,
                    membershipVersion = generation.membershipVersion,
                    mediaKeyEpoch = generation.mediaKeyEpoch,
                    sessionFactGeneration =
                        generation.conferenceEpoch * 1_000L + generation.membershipVersion,
                )
        }
    }

    fun clear() {
        sessions.clear()
        members.clear()
        lastSessionDeclarations.clear()
        membershipConvergence.clear()
    }

    private fun ByteArray.toConferenceIdHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
}

sealed class Profile01ValidationResult {
    data class ReadySession(
        val declaration: AuthoritativeConferenceMediaSessionDeclaration,
        val membershipRepublication: AuthoritativeConferenceMediaSessionDeclaration?,
    ) : Profile01ValidationResult()

    data class ReadyMembership(
        val generation: Profile01MembershipGeneration,
        val sessionRepublication: AuthoritativeConferenceMediaSessionDeclaration?,
    ) : Profile01ValidationResult()

    data class ReadyMember(
        val declaration: AuthoritativeConferenceMediaMemberDeclaration,
    ) : Profile01ValidationResult()

    data class NotPublished(
        val disposition: ConferenceMediaDeclarationDisposition,
        val reason: String,
    ) : Profile01ValidationResult()

    data class Invalid(val reason: String) : Profile01ValidationResult()
}
