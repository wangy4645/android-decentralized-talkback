package com.talkback.core.conference.session

import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.wire.RtpStructuralGate

/**
 * Resolve ingress source from session [SourceBindingCatalog] (authoritative SSRC binding).
 */
object SessionBindingIngressResolver {
    fun resolveBySsrc(
        datagram: ByteArray,
        catalog: SourceBindingCatalog,
    ): SourceBindingCatalog.Entry? {
        return when (val parsed = RtpStructuralGate.parse(datagram)) {
            is RtpStructuralGate.ParseResult.Ok -> catalog.resolveBySsrc(parsed.parsed.ssrc)
            is RtpStructuralGate.ParseResult.Reject -> null
        }
    }

    fun resolveBySsrc(
        datagram: ByteArray,
        fixtures: List<Phase1MediaHarness.SourceFixture>,
    ): String? =
        com.talkback.core.conference.transport.WireSourceIdentityResolver.resolveBySsrc(
            datagram,
            fixtures,
        )
}
