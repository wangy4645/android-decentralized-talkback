package com.talkback.core.conference.transport

import com.talkback.core.conference.wire.RtpStructuralGate

/**
 * Resolve conference source identity from cleartext RTP header (pre-SRTP admit).
 */
object WireSourceIdentityResolver {
    fun resolveBySsrc(
        datagram: ByteArray,
        fixtures: List<Phase1MediaHarness.SourceFixture>,
    ): String? {
        return when (val parsed = RtpStructuralGate.parse(datagram)) {
            is RtpStructuralGate.ParseResult.Ok ->
                fixtures.firstOrNull { it.ssrc == parsed.parsed.ssrc }?.sourceIdentity
            is RtpStructuralGate.ParseResult.Reject -> null
        }
    }
}
