package com.talkback.core.session

/**
 * Conference media JNI affinity (P0.1b + P0.1c).
 *
 * Coordinator records control facts and dispatches. PeerConnection JNI runs on
 * the per-edge executor (or the session relay executor), never on
 * talkback-coordinator.
 */
object ConferenceMediaJniAffinity {
    const val RELAY_FANOUT_PEER = "__relay__"

    fun edgeKey(sessionId: String, moduleId: String): String = "$sessionId|$moduleId"

    fun dispatch(
        executors: PeerMediaExecutors,
        sessionId: String,
        moduleId: String,
        taskType: EdgeMediaTaskType = EdgeMediaTaskType.OTHER,
        origin: String = taskType.name,
        jni: () -> Unit
    ) {
        executors.execute(edgeKey(sessionId, moduleId), taskType, origin, jni)
    }
}
