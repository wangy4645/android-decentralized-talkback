package com.talkback.core.session

/**
 * CPP avatar row semantics — pure consumer of [ConferencePresenceProjection.participants].
 * MUST NOT read receive-path, ICE, or [ConferenceParticipantViewState].
 */
enum class CppAvatarAvailability {
    NORMAL,
    JOINING,
    RECONNECTING,
    DEGRADED,
    CAPTURE_BLOCKED
}

data class CppAvatarParticipantRow(
    val moduleId: String,
    val isLocal: Boolean,
    val availability: CppAvatarAvailability,
    val speaking: Boolean
)

enum class CppPeerConnectivityAxis {
    CONNECTED,
    RECONNECTING,
    DEGRADED,
    SYNCING,
    INITIAL_JOIN
}

object ConferencePresenceAvatarBind {

    fun avatarRows(
        projection: ConferencePresenceProjection,
        localModuleId: String,
        speakingModuleId: String?,
        localCaptureBlocked: Boolean
    ): List<CppAvatarParticipantRow> =
        projection.participants
            .filter { it.membership == CppMembership.JOINED }
            .map { record ->
                val isLocal = record.moduleId.equals(localModuleId, ignoreCase = true)
                val speaking = speakingModuleId?.equals(record.moduleId, ignoreCase = true) == true
                val availability = resolveAvailability(
                    record = record,
                    isLocal = isLocal,
                    localCaptureBlocked = localCaptureBlocked,
                    recovering = record.moduleId in projection.recoveringPeers
                )
                CppAvatarParticipantRow(
                    moduleId = record.moduleId,
                    isLocal = isLocal,
                    availability = availability,
                    speaking = speaking
                )
            }

    fun peerConnectivityAxis(record: ParticipantPresenceRecord, recovering: Boolean): CppPeerConnectivityAxis? {
        if (record.membership != CppMembership.JOINED) return null
        if (record.mediaConnected) {
            return if (recovering) CppPeerConnectivityAxis.RECONNECTING else CppPeerConnectivityAxis.CONNECTED
        }
        return when {
            recovering -> CppPeerConnectivityAxis.RECONNECTING
            record.mediaRelation == CppMediaRelation.DEGRADED -> CppPeerConnectivityAxis.DEGRADED
            record.evidence == CppEvidence.STALE -> CppPeerConnectivityAxis.SYNCING
            record.mediaRelation == CppMediaRelation.NONE &&
                record.evidence == CppEvidence.UNKNOWN -> CppPeerConnectivityAxis.INITIAL_JOIN
            record.mediaRelation == CppMediaRelation.NONE -> CppPeerConnectivityAxis.INITIAL_JOIN
            else -> CppPeerConnectivityAxis.INITIAL_JOIN
        }
    }

    internal fun resolveAvailability(
        record: ParticipantPresenceRecord,
        isLocal: Boolean,
        localCaptureBlocked: Boolean,
        recovering: Boolean
    ): CppAvatarAvailability {
        if (isLocal && localCaptureBlocked) return CppAvatarAvailability.CAPTURE_BLOCKED
        if (record.mediaConnected) {
            return if (recovering) CppAvatarAvailability.RECONNECTING else CppAvatarAvailability.NORMAL
        }
        if (record.membership != CppMembership.JOINED) {
            error("avatarRows filters to JOINED only")
        }
        return when {
            recovering -> CppAvatarAvailability.RECONNECTING
            record.mediaRelation == CppMediaRelation.DEGRADED -> CppAvatarAvailability.DEGRADED
            record.evidence == CppEvidence.STALE -> CppAvatarAvailability.DEGRADED
            record.mediaRelation == CppMediaRelation.NONE &&
                record.evidence == CppEvidence.UNKNOWN -> CppAvatarAvailability.JOINING
            record.mediaRelation == CppMediaRelation.NONE -> CppAvatarAvailability.JOINING
            else -> CppAvatarAvailability.JOINING
        }
    }
}

