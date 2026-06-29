package com.talkback.app

/** One row in the Talk/Contacts endpoint list (gate-then-shape projection per endpoint). */
data class PeerDisplayRow(
    val endpointKey: String,
    val online: Boolean
)
