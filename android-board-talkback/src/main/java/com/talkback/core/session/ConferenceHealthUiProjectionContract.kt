package com.talkback.core.session

/**
 * Phase 3: room-facing UI reads ConferenceHealth only.
 * Runtime / peer recovering are diagnostic facts and must not override MEDIA_USABLE.
 * See docs/analysis/0056-phase-3-health-ui-contract.md
 */
enum class ConferenceRoomFacing {
    ONLINE,
    NOT_ONLINE
}

data class ConferenceHealthUiProjection(
    val roomFacing: ConferenceRoomFacing,
    val l4RoomState: ConferenceL4RoomState,
    val recoveryInFlightDiagnostic: Boolean,
    val recoveringPeerChrome: Set<String> = emptySet(),
) {
    val roomOnline: Boolean get() = l4RoomState == ConferenceL4RoomState.ONLINE
}

data class ConferenceHealthUiInput(
    val health: ConferenceHealth?,
    val runtime: ConferenceRuntimeState? = null,
    val recoveringPeerIds: Set<String> = emptySet()
)

object ConferenceHealthUiProjectionContract {

    fun project(input: ConferenceHealthUiInput): ConferenceHealthUiProjection {
        val health = input.health
        val l4RoomState = health?.l4RoomState ?: ConferenceL4RoomState.NOT_ESTABLISHED
        val roomFacing = if (l4RoomState == ConferenceL4RoomState.ONLINE) {
            ConferenceRoomFacing.ONLINE
        } else {
            ConferenceRoomFacing.NOT_ONLINE
        }
        return ConferenceHealthUiProjection(
            roomFacing = roomFacing,
            l4RoomState = l4RoomState,
            recoveryInFlightDiagnostic = health?.recoveryInFlight == true ||
                input.runtime?.edgeRecovering == true,
            recoveringPeerChrome = input.recoveringPeerIds
        )
    }
}
