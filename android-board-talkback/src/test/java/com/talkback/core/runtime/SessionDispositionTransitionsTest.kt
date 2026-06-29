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

class SessionDispositionTransitionsTest {
    private val session = TalkbackSession(
        id = "s1",
        type = SessionType.GROUP,
        local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
        channelId = "CH-1"
    )

    @Test
    fun suspend_resume_activeCycle() {
        assertTrue(SessionDispositionTransitions.suspend(session))
        assertEquals(SessionDisposition.SUSPENDED, session.disposition)
        assertTrue(session.isForegroundSuspended())

        assertTrue(SessionDispositionTransitions.beginResume(session))
        assertEquals(SessionDisposition.RESUMING, session.disposition)

        assertTrue(SessionDispositionTransitions.markActive(session))
        assertEquals(SessionDisposition.ACTIVE, session.disposition)
        assertFalse(session.isForegroundSuspended())
    }
}
