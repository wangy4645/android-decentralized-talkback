package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class Profile01LeaveFastPathDeskTest {
    @Test
    fun wireIngressFactory_acceptsBoundedDelayedScheduler() {
        val ticks = AtomicInteger(0)
        val ingress =
            MeetingProfile01FactWireIngressTestSupport.newWireIngress(
                sessionIndex = MeetingProfile01ConferenceSessionIndex(),
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                scheduleDelayed = { _, block -> ticks.incrementAndGet(); block() },
            )
        assertTrue(ingress.observability.snapshot().isNotEmpty())
        assertEquals(0, ticks.get())
    }
}
