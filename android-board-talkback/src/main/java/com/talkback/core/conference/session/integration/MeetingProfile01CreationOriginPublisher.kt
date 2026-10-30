package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01CreationAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelopeBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborEncoder

/**
 * Profile01 CREATION encode + sign seam (Step 0).
 *
 * Reads frozen authority snapshot only — does not allocate ids or key material.
 */
class MeetingProfile01CreationOriginPublisher(
    private val signer: Profile01SignedFactSigner,
) {
    val signerKeyVersion: Long = signer.signerKeyVersion

    fun publishCreation(snapshot: Profile01CreationAuthoritySnapshot): ByteArray {
        require(snapshot.ownerModuleId == signer.signerModuleId) {
            "signer must match CREATION ownerModuleId"
        }
        val fullCanonicalBytes = Profile01WireCborEncoder.encodeCreationFullFact(snapshot)
        val signature = signer.signFullFact(fullCanonicalBytes)
        return Profile01SignedFactEnvelopeBuilder.build(fullCanonicalBytes, signature)
    }

    companion object {
        fun fromPersistedSigner(
            signer: Profile01PersistedSignedFactSigner,
        ): MeetingProfile01CreationOriginPublisher = MeetingProfile01CreationOriginPublisher(signer)
    }
}
