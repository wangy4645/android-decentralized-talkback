package com.talkback.appprod.data

/**
 * Channel session mode on the default channel.
 * GROUP_PTT: half-duplex floor control (hold PTT).
 * CONFERENCE: full-duplex mesh (continuous mic, tap PTT to mute).
 */
enum class ChannelMode {
    GROUP_PTT,
    CONFERENCE;

    fun persistKey(): String = name

    companion object {
        fun fromPersisted(raw: String?): ChannelMode =
            when (raw?.uppercase()) {
                "CONFERENCE" -> CONFERENCE
                else -> GROUP_PTT
            }
    }
}
