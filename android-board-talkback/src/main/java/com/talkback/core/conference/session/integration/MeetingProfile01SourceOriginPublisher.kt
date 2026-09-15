package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelopeBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SourceDeclarationAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborEncoder

/**
 * Profile01 SOURCE_DECLARATION encode + sign seam (PR-PA-SR-B1).
 */
class MeetingProfile01SourceOriginPublisher(
    private val signer: Profile01SignedFactSigner,
) {
    val signerKeyVersion: Long = signer.signerKeyVersion
    val signerModuleId: String = signer.signerModuleId

    fun publishSource(snapshot: Profile01SourceDeclarationAuthoritySnapshot): ByteArray {
        require(snapshot.declaringModuleId == signer.signerModuleId) {
            "signer must match SOURCE declaringModuleId"
        }
        val fullCanonicalBytes = Profile01WireCborEncoder.encodeSourceDeclarationFullFact(snapshot)
        val signature = signer.signFullFact(fullCanonicalBytes)
        return Profile01SignedFactEnvelopeBuilder.build(fullCanonicalBytes, signature)
    }
}
