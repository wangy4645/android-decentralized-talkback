package com.talkback.core.webrtc

/** Outbound audio mode for anchor-relay group calls. */
enum class ProgramRelayMode {
    /** Send local microphone (default). */
    MICROPHONE,
    /** Forward program audio from the floor holder (anchor relay). */
    PROGRAM
}
