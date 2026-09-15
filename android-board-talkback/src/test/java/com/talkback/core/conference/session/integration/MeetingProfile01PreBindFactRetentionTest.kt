package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeetingProfile01PreBindFactRetentionTest {
    @Test
    fun readRoutingIdentity_extractsConferenceIdFromCreationBytes() {
        val routing =
            Profile01WireCborDecoder.readRoutingIdentity(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
            )
        assertNotNull(routing)
        assertEquals(Profile01DirectedWireFixtures.CONFERENCE_ID, routing!!.conferenceIdHex)
        assertEquals(1, routing.factType)
    }

    @Test
    fun retain_dedupesByFactDigest() {
        val retention = MeetingProfile01PreBindFactRetention()
        val signal = sampleSignal()
        val routing =
            Profile01WireCborDecoder.readRoutingIdentity(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
            )!!
        assertEquals(RetainOutcome.RETAINED, retention.retain(routing.conferenceIdHex, routing.factDigestHex, signal))
        assertEquals(RetainOutcome.DEDUPED, retention.retain(routing.conferenceIdHex, routing.factDigestHex, signal))
        assertEquals(1, retention.detachForDrain(routing.conferenceIdHex).size)
    }

    @Test
    fun retain_rejectsWhenPerConferenceCapExceeded() {
        val retention = MeetingProfile01PreBindFactRetention()
        val routing =
            Profile01WireCborDecoder.readRoutingIdentity(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
            )!!
        repeat(MeetingProfile01PreBindFactRetention.MAX_RETAINED_PER_CONFERENCE) { index ->
            val outcome =
                retention.retain(
                    routing.conferenceIdHex,
                    routing.factDigestHex + "-$index",
                    sampleSignal(nonce = "n$index"),
                )
            assertEquals(RetainOutcome.RETAINED, outcome)
        }
        val rejected =
            retention.retain(
                routing.conferenceIdHex,
                routing.factDigestHex + "-overflow",
                sampleSignal(nonce = "overflow"),
            )
        assertEquals(RetainOutcome.REJECTED_BOUNDS, rejected)
    }

    private fun sampleSignal(nonce: String = "wire-test-nonce"): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = "meeting-session-1",
            timestampMs = 1L,
            payload =
                Base64.getEncoder().encodeToString(
                    Profile01DirectedWireFixtures.creationSignedFactBytes,
                ),
            nonce = nonce,
            signature = "wire-test-signature",
        )
}
