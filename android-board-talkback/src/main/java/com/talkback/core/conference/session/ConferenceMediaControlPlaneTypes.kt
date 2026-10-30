package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding

/**
 * Authoritative control-plane session fact (GBC / Profile 01 upstream).
 *
 * Mapping-only input — lifecycle policy remains in [ConferenceSessionMediaWiring].
 */
data class ConferenceMediaSessionControlFact(
    val conferenceId: String,
    val channelId: String,
    /** Session media generation — maps to [ConferenceSessionMediaFact.generation]. */
    val conferenceEpoch: Long,
    /** Membership generation — transitional bridge to product [TalkbackSession.rosterEpoch]. */
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val endpoint: MediaGroupEndpointBinding,
    val networkInterfaceName: String,
    val masterKey: ByteArray,
    val masterSalt: ByteArray,
    val keyContextHint64: ByteArray,
    /** Monotonic publish sequence for this session fact stream. */
    val factGeneration: Long,
    val superseded: Boolean = false,
)

/**
 * Authoritative per-member wire binding from Source Declaration / Resolution (Profile 01).
 *
 * SSRC and [sourceAdmissionKey48] are never derived from [moduleId] in the adapter.
 */
data class ConferenceMediaMemberControlFact(
    val conferenceId: String,
    val moduleId: String,
    val membershipIncarnationId: Long,
    val ssrc: Int,
    val sourceAdmissionKey48: ByteArray,
    val mediaKeyEpoch: Long,
    val membershipVersion: Long,
    val factGeneration: Long,
    val superseded: Boolean = false,
)

enum class ControlFactPublishOutcome {
    ACCEPTED,
    IDEMPOTENT,
    REJECTED_STALE,
    REJECTED_SUPERSEDED,
    REJECTED_NO_SESSION,
    REJECTED_INCOHERENT,
    /** Upstream declaration not yet authoritative (PENDING / non-converged). */
    REJECTED_NOT_AUTHORITATIVE,
    /** Required binding fields missing or malformed. */
    REJECTED_INCOMPLETE,
}
