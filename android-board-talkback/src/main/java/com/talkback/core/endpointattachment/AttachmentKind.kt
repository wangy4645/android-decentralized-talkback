package com.talkback.core.endpointattachment

enum class AttachmentKind {
    IMAGE,
    VOICE,
    FILE,
    LOCATION;

    companion object {
        fun fromWire(raw: String): AttachmentKind? = when (raw.uppercase()) {
            "IMAGE" -> IMAGE
            "VOICE" -> VOICE
            "FILE" -> FILE
            "LOCATION" -> LOCATION
            else -> null
        }
    }
}
