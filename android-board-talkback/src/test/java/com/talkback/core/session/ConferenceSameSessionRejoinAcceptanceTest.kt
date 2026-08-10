package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConferenceSameSessionRejoinAcceptancePatch — admission predicate only.
 *
 * ```
 * ordinary duplicate          → reject (BUSY path)
 * Host rejoin + SDP + lineage → accept reconnect
 * ```
 */
class ConferenceSameSessionRejoinAcceptanceTest {

    private val sessionId = "conf-sess-1"
    private val host = "M01"
    private val participant = "M02"

    @Test
    fun hostRejoinWithSdp_sameSession_admitsReconnect() {
        assertTrue(
            ConferenceSameSessionRejoinAcceptance.shouldAcceptReconnect(
                existingType = SessionType.CONFERENCE,
                existingSessionId = sessionId,
                existingHostModuleId = host,
                inviteSessionId = sessionId,
                callerModuleId = host,
                payloadRejoin = true,
                payloadSdpBlank = false,
                payloadInitiatorModuleId = host
            )
        )
    }

    @Test
    fun ordinaryDuplicate_noRejoin_keepsBusy() {
        assertFalse(
            ConferenceSameSessionRejoinAcceptance.shouldAcceptReconnect(
                existingType = SessionType.CONFERENCE,
                existingSessionId = sessionId,
                existingHostModuleId = host,
                inviteSessionId = sessionId,
                callerModuleId = host,
                payloadRejoin = false,
                payloadSdpBlank = false,
                payloadInitiatorModuleId = host
            )
        )
    }

    @Test
    fun rejoinWithoutSdp_keepsBusy() {
        assertFalse(
            ConferenceSameSessionRejoinAcceptance.shouldAcceptReconnect(
                existingType = SessionType.CONFERENCE,
                existingSessionId = sessionId,
                existingHostModuleId = host,
                inviteSessionId = sessionId,
                callerModuleId = host,
                payloadRejoin = true,
                payloadSdpBlank = true,
                payloadInitiatorModuleId = host
            )
        )
    }

    @Test
    fun wrongCaller_notHost_keepsBusy() {
        assertFalse(
            ConferenceSameSessionRejoinAcceptance.shouldAcceptReconnect(
                existingType = SessionType.CONFERENCE,
                existingSessionId = sessionId,
                existingHostModuleId = host,
                inviteSessionId = sessionId,
                callerModuleId = participant,
                payloadRejoin = true,
                payloadSdpBlank = false,
                payloadInitiatorModuleId = host
            )
        )
    }

    @Test
    fun initiatorMismatch_keepsBusy() {
        assertFalse(
            ConferenceSameSessionRejoinAcceptance.shouldAcceptReconnect(
                existingType = SessionType.CONFERENCE,
                existingSessionId = sessionId,
                existingHostModuleId = host,
                inviteSessionId = sessionId,
                callerModuleId = host,
                payloadRejoin = true,
                payloadSdpBlank = false,
                payloadInitiatorModuleId = "M99"
            )
        )
    }

    @Test
    fun differentSessionId_keepsBusy() {
        assertFalse(
            ConferenceSameSessionRejoinAcceptance.shouldAcceptReconnect(
                existingType = SessionType.CONFERENCE,
                existingSessionId = sessionId,
                existingHostModuleId = host,
                inviteSessionId = "other-sess",
                callerModuleId = host,
                payloadRejoin = true,
                payloadSdpBlank = false,
                payloadInitiatorModuleId = host
            )
        )
    }

    @Test
    fun groupSession_neverAdmittedHere() {
        assertFalse(
            ConferenceSameSessionRejoinAcceptance.shouldAcceptReconnect(
                existingType = SessionType.GROUP,
                existingSessionId = sessionId,
                existingHostModuleId = host,
                inviteSessionId = sessionId,
                callerModuleId = host,
                payloadRejoin = true,
                payloadSdpBlank = false,
                payloadInitiatorModuleId = host
            )
        )
    }
}
