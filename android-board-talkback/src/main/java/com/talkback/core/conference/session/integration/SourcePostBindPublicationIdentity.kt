package com.talkback.core.conference.session.integration

/**
 * Post-bind SOURCE consumption delivery identity (PR-PA-SR-B1-R2).
 *
 * Separate from [SourcePublicationIdentity] transport publication.
 */
data class SourcePostBindPublicationIdentity(
    val conferenceId: String,
    val sourceModuleId: String,
    val sourceGeneration: Long,
    val sourceInstanceIdHex: String,
    val targetModuleId: String,
    val factDigest: String,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
)
