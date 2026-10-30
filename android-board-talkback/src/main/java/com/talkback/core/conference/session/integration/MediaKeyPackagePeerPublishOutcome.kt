package com.talkback.core.conference.session.integration

/**
 * Single-peer MEDIA_KEY_PACKAGE send attempt (no ledger side effects).
 */
sealed class MediaKeyPackagePeerPublishOutcome {
    data class ReadyToSend(
        val identity: MediaKeyPackagePublicationIdentity,
    ) : MediaKeyPackagePeerPublishOutcome()

    data class Sent(
        val identity: MediaKeyPackagePublicationIdentity,
    ) : MediaKeyPackagePeerPublishOutcome()

    data class PendingIdentity(
        val recipientModuleId: String,
        val reason: String,
    ) : MediaKeyPackagePeerPublishOutcome()

    data class Skipped(
        val reason: String,
    ) : MediaKeyPackagePeerPublishOutcome()

    data class SendFailed(
        val identity: MediaKeyPackagePublicationIdentity?,
    ) : MediaKeyPackagePeerPublishOutcome()

    data class BuildFailed(
        val recipientModuleId: String,
        val reason: String,
    ) : MediaKeyPackagePeerPublishOutcome()
}
