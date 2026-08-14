package com.talkback.core.webrtc.conferenceaudio

/**
 * ADR-0056 Phase 1a-3 — conference participant outbound media semantics.
 * Distinct from GROUP [ProgramRelayMode] (PTT floor relay).
 */
enum class ParticipantMediaMode {
    /** Local microphone → participant output (WebRTC capture path). */
    LOCAL,

    /** Remote relay / mixed audio → participant output (program injection path). */
    REMOTE_RELAY,

    /** Anchor: local microphone + remote relay → mixer → participant output. */
    LOCAL_AND_REMOTE
}

object ParticipantMediaModePolicy {
    const val LOCAL_MIC_SOURCE_ID = "__local_mic__"

    fun resolve(
        localModuleId: String,
        anchorModuleId: String,
        participantModuleId: String
    ): ParticipantMediaMode = when {
        participantModuleId == localModuleId && participantModuleId == anchorModuleId ->
            ParticipantMediaMode.LOCAL_AND_REMOTE
        participantModuleId == localModuleId ->
            ParticipantMediaMode.LOCAL
        else ->
            ParticipantMediaMode.REMOTE_RELAY
    }

    fun includesLocalMicrophone(mode: ParticipantMediaMode): Boolean =
        mode == ParticipantMediaMode.LOCAL || mode == ParticipantMediaMode.LOCAL_AND_REMOTE

    fun includesRemoteRelay(mode: ParticipantMediaMode): Boolean =
        mode == ParticipantMediaMode.REMOTE_RELAY || mode == ParticipantMediaMode.LOCAL_AND_REMOTE
}
