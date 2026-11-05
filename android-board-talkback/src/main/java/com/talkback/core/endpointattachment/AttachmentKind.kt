package com.talkback.core.endpointattachment

enum class AttachmentKind {
    IMAGE,
    VOICE,
    FILE;

    companion object {
        fun fromWire(raw: String): AttachmentKind? = when (raw.uppercase()) {
            "IMAGE" -> IMAGE
            "VOICE" -> VOICE
            "FILE" -> FILE
            else -> null
        }
    }
}
