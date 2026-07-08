package com.talkback.core.session

/**
 * Conference availability phase projected from control + media facts (ADR-0010 / CONTEXT).
 * UI must consume [ConferenceRuntimeState] only - not raw ICE or [ChannelReadiness].
 */
enum class ConferenceRuntimePhase {
    IDLE,
    CONNECTING,
    ACTIVE,
    RECOVERING
}