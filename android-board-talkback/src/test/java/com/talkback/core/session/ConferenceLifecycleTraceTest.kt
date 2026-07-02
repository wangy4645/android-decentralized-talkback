package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ConferenceLifecycleTraceTest {
    @Test
    fun sessionSnapshot_format_isStable() {
        val snap = ConferenceSessionSnapshot(
            participants = 3,
            connected = 2,
            authority = "M02",
            local = "M01"
        )
        assertEquals(
            "snapshot={participants=3,connected=2,authority=M02,local=M01}",
            snap.format()
        )
    }
}
