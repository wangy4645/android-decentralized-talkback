package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot

/**
 * Production local establishment possession surface (EP production wiring).
 *
 * Missing profile, Keystore identity, or binding mismatch → explicit unavailable diagnostic.
 * No auto-provision, rotation, or signer fallback.
 */
sealed class Profile01LocalEstablishmentIdentityAvailability {
    data class Verified(
        val identity: Profile01LocalEstablishmentIdentitySnapshot,
    ) : Profile01LocalEstablishmentIdentityAvailability()

    data class Unavailable(
        val diagnostic: String,
    ) : Profile01LocalEstablishmentIdentityAvailability()
}
