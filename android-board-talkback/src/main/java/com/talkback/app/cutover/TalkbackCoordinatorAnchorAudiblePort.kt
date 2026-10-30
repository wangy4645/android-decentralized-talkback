package com.talkback.app.cutover

import com.talkback.app.TalkbackCoordinator
import com.talkback.core.conference.session.integration.cutover.AnchorAudiblePort

internal class TalkbackCoordinatorAnchorAudiblePort(
    private val coordinator: TalkbackCoordinator,
) : AnchorAudiblePort {
    override fun releaseAnchorOwnership(sessionId: String): Boolean =
        coordinator.replacementCutoverReleaseAnchorAudible(sessionId)

    override fun acquireAnchorOwnership(sessionId: String): Boolean =
        coordinator.replacementCutoverAcquireAnchorAudible(sessionId)

    override fun isAnchorAudibleActive(sessionId: String): Boolean =
        coordinator.replacementCutoverIsAnchorAudibleActive(sessionId)
}
