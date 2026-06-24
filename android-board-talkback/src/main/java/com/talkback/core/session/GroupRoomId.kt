package com.talkback.core.session

/**
 * Deterministic GROUP session id shared by all nodes on a channel.
 * Replaces per-attempt random UUIDs so mesh converges after unicast or reconnect.
 */
object GroupRoomId {
    fun forChannel(channelId: String): String = "grp:$channelId"
}
