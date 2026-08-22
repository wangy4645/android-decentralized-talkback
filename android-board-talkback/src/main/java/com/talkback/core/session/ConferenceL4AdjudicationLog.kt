package com.talkback.core.session

/**
 * AUTH-B-5: field grep for Phase B L4 adjudication.
 */
object ConferenceL4AdjudicationLog {

    fun format(
        conferenceId: String,
        meshGeneration: Long,
        result: ConferenceL4AdjudicationResult,
    ): String {
        val triggers = result.criticalTriggers.joinToString(",") { it.name }
        return "CONFERENCE_L4_ADJUDICATED" +
            " conferenceId=$conferenceId" +
            " meshGeneration=$meshGeneration" +
            " mediaUsable=${result.mediaUsable}" +
            " l4RoomState=${result.l4RoomState.name}" +
            " anchorAuthoritative=${result.anchorAuthoritative}" +
            " criticalTriggers=$triggers"
    }
}
