package com.talkback.core.session

/**
 * ViewModel/UI may only read these. MUST NOT recompute aggregates from ICE / reachability.
 */
object ConferencePresenceUiBind {

    fun avatarRows(
        projection: ConferencePresenceProjection,
        localModuleId: String,
        speakingModuleId: String?,
        localCaptureBlocked: Boolean
    ): List<CppAvatarParticipantRow> = ConferencePresenceAvatarBind.avatarRows(
        projection = projection,
        localModuleId = localModuleId,
        speakingModuleId = speakingModuleId,
        localCaptureBlocked = localCaptureBlocked
    )

    fun joiningHint(
        projection: ConferencePresenceProjection,
        localCaptureBlocked: Boolean
    ): String? {
        if (localCaptureBlocked) return "Microphone unavailable"
        val joining = projection.participants.filter {
            it.membership == CppMembership.JOINED &&
                !it.mediaConnected &&
                it.moduleId !in projection.recoveringPeers
        }
        return when (joining.size) {
            0 -> null
            1 -> "${joining.single().moduleId} joining..."
            else -> "${joining.size} joining..."
        }
    }
}
