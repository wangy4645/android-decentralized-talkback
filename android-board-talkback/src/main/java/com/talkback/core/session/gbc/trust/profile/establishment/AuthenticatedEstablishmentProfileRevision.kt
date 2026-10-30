package com.talkback.core.session.gbc.trust.profile.establishment

/**
 * PAP-authenticated establishment profile semantics.
 *
 * PR-EP-1 acceptor consumes **only** this type. Upstream PAP verification is a prerequisite;
 * the acceptor does not re-authenticate profile bytes.
 */
class AuthenticatedEstablishmentProfileRevision internal constructor(
    val payload: EstablishmentProfileTrustPayload,
    internal val protectedSemanticBytes: ByteArray,
) {
    val revisionIdentity: ByteArray =
        EstablishmentRevisionIdentity.fromProtectedBytes(protectedSemanticBytes)

    companion object {
        /**
         * Called by the upstream PAP gate after authentication succeeds.
         * Rejects non-canonical protected bytes.
         */
        fun fromPapAuthenticatedSemantics(
            protectedBytes: ByteArray,
        ): AuthenticatedEstablishmentProfileRevision? {
            val payload =
                EstablishmentProfileRevisionCanonicalCodec.decode(protectedBytes)
                    ?: return null
            val canonical = EstablishmentProfileRevisionCanonicalCodec.encode(payload)
            if (!canonical.contentEquals(protectedBytes)) return null
            return AuthenticatedEstablishmentProfileRevision(payload, canonical)
        }

        internal fun forValidatedSemantics(
            payload: EstablishmentProfileTrustPayload,
        ): AuthenticatedEstablishmentProfileRevision {
            val canonical = EstablishmentProfileRevisionCanonicalCodec.encode(payload)
            return AuthenticatedEstablishmentProfileRevision(payload, canonical)
        }
    }
}
