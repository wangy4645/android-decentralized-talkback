package com.talkback.core.ptt

enum class PttState {
    IDLE,
    REQUEST_FLOOR,
    TALK,
    RELEASE_FLOOR
}

sealed class PttEvent {
    data object Press : PttEvent()
    data object Granted : PttEvent()
    data object Rejected : PttEvent()
    data object Release : PttEvent()
    data object RemoteHangup : PttEvent()
    data object Timeout : PttEvent()
}

class PttStateMachine {
    var state: PttState = PttState.IDLE
        private set

    fun onEvent(event: PttEvent): PttState {
        state = when (state) {
            PttState.IDLE -> when (event) {
                PttEvent.Press -> PttState.REQUEST_FLOOR
                else -> PttState.IDLE
            }

            PttState.REQUEST_FLOOR -> when (event) {
                PttEvent.Granted -> PttState.TALK
                PttEvent.Rejected, PttEvent.Timeout, PttEvent.Release -> PttState.IDLE
                else -> PttState.REQUEST_FLOOR
            }

            PttState.TALK -> when (event) {
                PttEvent.Release -> PttState.RELEASE_FLOOR
                PttEvent.RemoteHangup -> PttState.IDLE
                else -> PttState.TALK
            }

            PttState.RELEASE_FLOOR -> when (event) {
                PttEvent.Press -> PttState.REQUEST_FLOOR
                else -> PttState.IDLE
            }
        }
        return state
    }
}
