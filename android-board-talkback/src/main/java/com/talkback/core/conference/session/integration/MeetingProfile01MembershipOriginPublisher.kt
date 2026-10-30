package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01MembershipAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelopeBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborEncoder

/**
 * Profile01 MEMBERSHIP encode + sign seam (RCA4).
 */
class MeetingProfile01MembershipOriginPublisher(
    private val signer: Profile01SignedFactSigner,
) {
    val signerKeyVersion: Long = signer.signerKeyVersion

    fun publishMembership(snapshot: Profile01MembershipAuthoritySnapshot): ByteArray {
        require(snapshot.ownerModuleId == signer.signerModuleId) {
            "signer must match MEMBERSHIP ownerModuleId"
        }
        val fullCanonicalBytes = Profile01WireCborEncoder.encodeMembershipFullFact(snapshot)
        val signature = signer.signFullFact(fullCanonicalBytes)
        return Profile01SignedFactEnvelopeBuilder.build(fullCanonicalBytes, signature)
    }
}
