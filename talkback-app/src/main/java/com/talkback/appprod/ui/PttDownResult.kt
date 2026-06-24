package com.talkback.appprod.ui

sealed class PttDownResult {
    data object Ok : PttDownResult()
    data object Connecting : PttDownResult()
    data object NoPeers : PttDownResult()
    data object ServiceStopped : PttDownResult()
    data object NoTeammates : PttDownResult()
    data class FloorBusy(val speaker: String) : PttDownResult()
    data object MeetingActive : PttDownResult()
}
