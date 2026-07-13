package com.talkback.app

/** Local memory of a conference the user left voluntarily and may rejoin without a new invite. */
data class RejoinableConferenceSnapshot(
    val hostSessionId: String,
    val channelId: String,
    val hostModuleId: String,
    val hostKey: String,
    val leftAtMs: Long,
    val membershipEpoch: Long = 0L
)
