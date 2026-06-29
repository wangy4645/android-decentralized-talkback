package com.talkback.core.runtime

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.SessionDisposition
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleActivityStackTest {
    private val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
    private val stack = ModuleActivityStack()

    @Test
    fun pushPop_tracksTopSession() {
        stack.push(
            ActivityFrame(
                activityType = ActivityType.UNICAST,
                sessionId = "u1",
                actingEndpointId = local.key,
                requestedBy = local.key,
                preemptReason = PreemptReason.UNICAST_PREEMPT
            )
        )
        assertEquals("u1", stack.topSessionId())
        assertEquals(local.key, stack.topActingEndpointId())
        assertEquals("u1", stack.popIfSession("u1")?.sessionId)
        assertTrue(stack.top() == null)
    }

    @Test
    fun preemptionToken_onlyMatchingForegroundMayResume() {
        stack.recordPreemption(
            PreemptionToken(
                suspendedSessionId = "g1",
                preemptedBySessionId = "u1",
                preemptReason = PreemptReason.UNICAST_PREEMPT,
                actingEndpointId = local.key
            )
        )
        assertFalse(stack.canResume("g1", "u2"))
        assertTrue(stack.consumeResume("g1", "u1"))
        assertTrue(stack.canResume("g1", "u1"))
    }
}
