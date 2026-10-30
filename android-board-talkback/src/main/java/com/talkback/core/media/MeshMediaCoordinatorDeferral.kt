package com.talkback.core.media

/**
 * Schedules media lifecycle work on a coordinator turn after the current one (A0.1 handoff).
 */
fun interface MeshMediaCoordinatorDeferral {
    fun afterCurrentCoordinatorTurn(block: () -> Unit)
}
