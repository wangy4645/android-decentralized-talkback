package com.talkback.core.conference.lifecycle

import com.talkback.core.conference.lifecycle.ConferenceGroupReclaimDecision.Snapshot
import com.talkback.core.conference.lifecycle.ConferenceGroupReclaimDecision.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceGroupReclaimDecisionTest {
    @Test
    fun inviteeAcceptedBeforeIce_isEstablishing() {
        val snap = participant(
            accepted = true,
            hostIceState = null,
            hasEstablishingInvite = false
        )
        assertTrue(ConferenceGroupReclaimDecision.isEstablishing(snap))
        assertEquals(Verdict.SKIP_ESTABLISHING, ConferenceGroupReclaimDecision.verdict(snap))
    }

    @Test
    fun inviteeNewIce_isEstablishing() {
        val snap = participant(accepted = true, hostIceState = "NEW")
        assertEquals(Verdict.SKIP_ESTABLISHING, ConferenceGroupReclaimDecision.verdict(snap))
    }

    @Test
    fun hostOwned_neverReclaimed() {
        val snap = Snapshot(
            candidateSessionId = "s1",
            liveSessionId = "s1",
            accepted = true,
            isHostOwned = true,
            hostIceState = null,
            hasEstablishingInvite = false,
            operational = false
        )
        assertEquals(Verdict.SKIP_HOST_EXISTS, ConferenceGroupReclaimDecision.verdict(snap))
        assertFalse(ConferenceGroupReclaimDecision.isEstablishing(snap))
    }

    @Test
    fun historicalAccepted_notLiveSession_canReclaim() {
        val snap = participant(
            accepted = true,
            hostIceState = null,
            liveSessionId = "other-live"
        )
        assertFalse(ConferenceGroupReclaimDecision.isEstablishing(snap))
        assertEquals(Verdict.RECLAIM, ConferenceGroupReclaimDecision.verdict(snap))
    }

    @Test
    fun acceptedButHostIceFailed_isZombieReclaim() {
        val snap = participant(accepted = true, hostIceState = "FAILED")
        assertFalse(ConferenceGroupReclaimDecision.isEstablishing(snap))
        assertEquals(Verdict.RECLAIM, ConferenceGroupReclaimDecision.verdict(snap))
    }

    @Test
    fun unacceptedWithoutInvite_reclaim() {
        val snap = participant(accepted = false, hasEstablishingInvite = false, hostIceState = null)
        assertEquals(Verdict.RECLAIM, ConferenceGroupReclaimDecision.verdict(snap))
    }

    @Test
    fun ringingInvite_establishing() {
        val snap = participant(accepted = false, hasEstablishingInvite = true, hostIceState = "CHECKING")
        assertEquals(Verdict.SKIP_ESTABLISHING, ConferenceGroupReclaimDecision.verdict(snap))
    }

    private fun participant(
        accepted: Boolean,
        hostIceState: String?,
        hasEstablishingInvite: Boolean = false,
        liveSessionId: String? = "s1",
        operational: Boolean = false
    ) = Snapshot(
        candidateSessionId = "s1",
        liveSessionId = liveSessionId,
        accepted = accepted,
        isHostOwned = false,
        hostIceState = hostIceState,
        hasEstablishingInvite = hasEstablishingInvite,
        operational = operational
    )
}
