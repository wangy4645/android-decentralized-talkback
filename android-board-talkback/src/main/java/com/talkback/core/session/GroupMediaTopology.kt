package com.talkback.core.session

/**
 * Group media topology: full mesh (small teams) or anchor relay (6+ modules).
 */
enum class GroupMediaTopology {
    MESH,
    ANCHOR;

    fun encode(): String = name

    companion object {
        fun fromPayload(raw: String?): GroupMediaTopology =
            when (raw?.uppercase()) {
                ANCHOR.name -> ANCHOR
                else -> MESH
            }
    }
}
