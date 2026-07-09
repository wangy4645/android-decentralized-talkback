package com.talkback.core.model

/** ADR-0021 D1: distinguishes first admission from membership-preserving recovery. */
enum class ConferenceJoinIntent {
    NORMAL_JOIN,
    RECOVERY_REATTACH;

    fun encode(): String = name

    companion object {
        fun fromPayload(raw: String?): ConferenceJoinIntent =
            values().firstOrNull { it.name == raw } ?: NORMAL_JOIN
    }
}