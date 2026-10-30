package com.talkback.core.session

import com.talkback.core.util.TalkbackLog

/**
 * Field grep for Phase 2-5 ConferenceHealth projection.
 *
 * `CONFERENCE_HEALTH conferenceId=... meshGeneration=... topologyMode=ANCHOR anchorHealthModel=true mediaUsable=true userFacing=ONLINE recoveryInFlight=false recoveryFailed=false`
 */
object ConferenceHealthProjectionLog {

    fun format(health: ConferenceHealth): String {
        val facing = when (health.l4RoomState) {
            ConferenceL4RoomState.ONLINE -> "ONLINE"
            ConferenceL4RoomState.DEGRADED -> "DEGRADED"
            ConferenceL4RoomState.CONFERENCE_FAILED -> "CONFERENCE_FAILED"
            ConferenceL4RoomState.NOT_ESTABLISHED -> "NOT_ONLINE"
        }
        return "CONFERENCE_HEALTH" +
            " conferenceId=${health.conferenceId}" +
            " meshGeneration=${health.meshGeneration}" +
            " topologyMode=${health.topologyMode.name}" +
            " anchorHealthModel=${health.anchorHealthModel}" +
            " mediaUsable=${health.mediaUsable}" +
            " l4RoomState=${health.l4RoomState.name}" +
            " userFacing=$facing" +
            " recoveryInFlight=${health.recoveryInFlight}" +
            " recoveryFailed=${health.recoveryFailed}"
    }

    fun emit(line: String) {
        TalkbackLog.i(line)
    }
}
