package com.talkback.core.conference.session.profile01.wire

/**
 * Host-side lookup for peer RSA key-establishment public keys (MEDIA_KEY_PACKAGE wrap).
 *
 * MUST NOT fall back to ECDSA signing keys on miss.
 */
data class Profile01EstablishmentKeyRef(
    val recipientModuleId: String,
    val establishmentKeyVersion: Long,
    val establishmentPublicKeySpki: ByteArray,
)

sealed class Profile01EstablishmentKeyLookupResult {
    data class Found(val ref: Profile01EstablishmentKeyRef) : Profile01EstablishmentKeyLookupResult()

    data object UnknownModule : Profile01EstablishmentKeyLookupResult()

    data object UnknownKeyVersion : Profile01EstablishmentKeyLookupResult()

    data object KeyNotUsable : Profile01EstablishmentKeyLookupResult()
}

interface Profile01RecipientEstablishmentKeyLookup {
    fun lookupEstablishmentKey(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): Profile01EstablishmentKeyLookupResult

    fun activeEstablishmentKey(recipientModuleId: String): Profile01EstablishmentKeyLookupResult
}
