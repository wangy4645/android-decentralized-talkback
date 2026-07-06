package com.talkback.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelObservabilityLogTest {
    @Test
    fun shortStack_returnsNonEmpty() {
        val stack = ChannelObservabilityLog.shortStack(skipFrames = 0, frameCount = 2)
        assertTrue(stack.isNotBlank())
    }

    @Test
    fun channelModeTransition_noOpWhenSameMode() {
        // Smoke: should not throw when from == to (guarded in implementation).
        ChannelObservabilityLog.channelModeTransition(
            channelId = "CH-01",
            from = com.talkback.core.session.ChannelMode.IDLE,
            to = com.talkback.core.session.ChannelMode.IDLE,
            byModuleId = null,
            op = "test"
        )
    }
}
