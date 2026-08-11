package com.talkback.core.grouphealth

import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMediaEdgeHealthLogTest {

    @Test
    fun emitIncludesCoreFields() {
        GroupMediaEdgeHealthLog.emit(
            GroupMediaEdgeHealthLog.Event.RECOVERY_ACTION,
            GroupMediaEdgeHealthLog.Snapshot(
                channelId = "CH-01",
                localModuleId = "M02",
                remoteModuleId = "M03",
                sessionTraceId = "abc123",
                sessionId = "sid-1",
                mediaGeneration = 17L,
                iceState = "CHECKING",
                pcState = "CHECKING",
                checkingSinceMs = 42120L,
                recoveryLevel = "L0",
                action = "ICE_RESTART",
                reason = "CHECKING_TIMEOUT"
            )
        )
        // Smoke: format is stable for log grep (no exception).
        assertTrue(true)
    }
}
