package com.talkback.core.conference.session.gbc

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding

/**
 * Verified Profile 01 session declaration at GBC convergence boundary.
 *
 * Publisher bridge consumes these only — no lifecycle policy here.
 */
data class AuthoritativeConferenceMediaSessionDeclaration(
    val conferenceId: String,
    val channelId: String,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val endpoint: MediaGroupEndpointBinding,
    val networkInterfaceName: String,
    val masterKey: ByteArray,
    val masterSalt: ByteArray,
    val keyContextHint64: ByteArray,
    val factGeneration: Long,
    val disposition: ConferenceMediaDeclarationDisposition,
)

/**
 * Verified Source Declaration / Resolution at GBC convergence boundary.
 *
 * SSRC and admission key are authoritative wire facts — never derived from [moduleId].
 */
data class AuthoritativeConferenceMediaMemberDeclaration(
    val conferenceId: String,
    val moduleId: String,
    val membershipIncarnationId: Long,
    val ssrc: Int,
    val sourceAdmissionKey48: ByteArray,
    val mediaKeyEpoch: Long,
    val membershipVersion: Long,
    val factGeneration: Long,
    val disposition: ConferenceMediaDeclarationDisposition,
)

enum class ConferenceMediaDeclarationDisposition {
    /** Not yet converged — MUST NOT publish to media registry. */
    PENDING,
    /** Converged authoritative — eligible for registry publish. */
    AUTHORITATIVE,
    /** Superseded by newer declaration — MUST NOT install. */
    SUPERSEDED,
}
