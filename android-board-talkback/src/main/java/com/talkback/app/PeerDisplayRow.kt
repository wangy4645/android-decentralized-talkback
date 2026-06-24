package com.talkback.app

/** One row in the Talk/Contacts endpoint list (V1: one primary endpoint per remote module). */
data class PeerDisplayRow(
    val endpointKey: String,
    val online: Boolean
)
