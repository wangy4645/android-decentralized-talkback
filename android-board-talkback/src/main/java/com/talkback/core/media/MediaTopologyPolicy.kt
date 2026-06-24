package com.talkback.core.media

/**
 * Documents and enforces module-level media topology limits.
 * Endpoint-level WebRTC mesh is intentionally not supported.
 */
object MediaTopologyPolicy {
    const val DEFAULT_MAX_GROUP_MODULES = 8
    const val DEFAULT_MAX_CONFERENCE_MODULES = 8
    const val RECOMMENDED_MESH_MODULES = 5
    const val RECOMMENDED_CONFERENCE_MODULES = 8
    const val SFU_LITE_THRESHOLD_MODULES = 6
    /** Channel GROUP PTT uses anchor relay at or above this member count. */
    const val CHANNEL_ANCHOR_THRESHOLD_MODULES = 99
    /** Conference switches to anchor relay at or above this member count (4~8 person meetings). */
    const val SFU_LITE_CONFERENCE_THRESHOLD_MODULES = 4

    fun shouldWarnLargeGroup(memberCount: Int): Boolean = memberCount > RECOMMENDED_MESH_MODULES

    fun shouldWarnLargeConference(memberCount: Int): Boolean =
        memberCount > RECOMMENDED_CONFERENCE_MODULES

    fun anchorThresholdForGroup(): Int = CHANNEL_ANCHOR_THRESHOLD_MODULES

    fun anchorThresholdForConference(): Int = SFU_LITE_CONFERENCE_THRESHOLD_MODULES
}
