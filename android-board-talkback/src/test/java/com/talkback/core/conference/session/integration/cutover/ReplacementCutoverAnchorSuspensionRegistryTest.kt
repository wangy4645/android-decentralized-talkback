package com.talkback.core.conference.session.integration.cutover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplacementCutoverAnchorSuspensionRegistryTest {
    @Test
    fun sessionTeardown_clearsSuspensionLatchWithoutRestoreSideEffects() {
        val sessionId = "session-teardown-latch"
        ReplacementCutoverAnchorSuspensionRegistry.suspend(sessionId)
        assertTrue(ReplacementCutoverAnchorSuspensionRegistry.contains(sessionId))

        ReplacementCutoverAnchorSuspensionRegistry.clearOnSessionTeardown(sessionId)

        assertFalse(ReplacementCutoverAnchorSuspensionRegistry.contains(sessionId))
    }
}
