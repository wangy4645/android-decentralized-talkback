package com.talkback.core.model

/**
 * Distinguishes half-duplex group PTT from full-duplex conference on the same mesh signaling.
 */
enum class MeshSessionMode {
    GROUP,
    CONFERENCE;

    fun encode(): String = name

    companion object {
        fun fromPayload(raw: String?): MeshSessionMode =
            when (raw?.uppercase()) {
                "CONFERENCE" -> CONFERENCE
                else -> GROUP
            }
    }
}
