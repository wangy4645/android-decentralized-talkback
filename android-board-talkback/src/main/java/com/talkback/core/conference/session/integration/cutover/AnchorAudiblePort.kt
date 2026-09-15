package com.talkback.core.conference.session.integration.cutover

interface AnchorAudiblePort {
    fun releaseAnchorOwnership(sessionId: String): Boolean

    fun acquireAnchorOwnership(sessionId: String): Boolean

    fun isAnchorAudibleActive(sessionId: String): Boolean
}
