package com.talkback.core.session

import com.talkback.core.util.TalkbackLog

/**
 * Field grep for CPP 4p Presence Truth Gate. Behavior-neutral.
 *
 * `CONFERENCE_PRESENCE_PROJECTION conferenceId=... local=... producer=... rosterEpoch=... anchorEpoch=... meshGeneration=... joined=N connected=N joining=N recovering=... participants=M01:JOINED:VIA_ANCHOR:FRESH,...`
 */
object ConferencePresenceProjectionLog {

    fun format(
        conferenceId: String,
        localModuleId: String,
        producerModuleId: String,
        rosterEpoch: Long,
        anchorEpoch: Long,
        meshGeneration: Long,
        projection: ConferencePresenceProjection
    ): String {
        val recovering = projection.recoveringPeers.sorted().joinToString(",")
        val parts = projection.participants.joinToString(",") { rec ->
            "${rec.moduleId}:${rec.membership}:${rec.mediaRelation}:${rec.evidence}"
        }
        return "CONFERENCE_PRESENCE_PROJECTION" +
            " conferenceId=$conferenceId" +
            " local=$localModuleId" +
            " producer=$producerModuleId" +
            " rosterEpoch=$rosterEpoch" +
            " anchorEpoch=$anchorEpoch" +
            " meshGeneration=$meshGeneration" +
            " joined=${projection.joinedCount}" +
            " connected=${projection.connectedCount}" +
            " joining=${projection.joiningCount}" +
            " recovering=$recovering" +
            " participants=$parts"
    }

    fun emit(line: String) {
        TalkbackLog.i(line)
    }
}
