package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceMediaJniAffinity

/** Remote module id from `sessionId|remoteModuleId` edge key. */
fun ConferenceFailureTerminal.remoteModuleId(): String? =
    remoteModuleIdFromEdgeKey(edgeKey)

fun remoteModuleIdFromEdgeKey(edgeKey: String): String? {
    val remote = edgeKey.substringAfter('|', missingDelimiterValue = "")
    return remote.takeIf { it.isNotBlank() }
}

fun edgeKeyForRemote(sessionId: String, remoteModuleId: String): String =
    ConferenceMediaJniAffinity.edgeKey(sessionId, remoteModuleId)
