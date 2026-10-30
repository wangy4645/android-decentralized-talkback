package com.talkback.core.conference.session.integration.cutover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConferenceProductionMediaReadinessTest {
    @Test
    fun unaccepted_isBlocked() {
        val r =
            ConferenceProductionMediaReadiness.evaluate(
                sessionId = "s1",
                localModuleId = "M03",
                accepted = false,
                isHost = false,
            )
        assertFalse(r.ready)
        assertEquals(ConferenceProductionMediaReadiness.SESSION_NOT_ACCEPTED, r.block)
    }

    @Test
    fun acceptedWithoutMaterializedSession_isBlocked() {
        val r =
            ConferenceProductionMediaReadiness.evaluate(
                sessionId = "missing-session",
                localModuleId = "M03",
                accepted = true,
                isHost = false,
            )
        assertFalse(r.ready)
        assertEquals(ConferenceProductionMediaReadiness.MULTICAST_SESSION_NOT_MATERIALIZED, r.block)
    }
}
