package com.talkback.core.conference.session.profile01.wire

/**
 * RSA unwrap for MEDIA_KEY_PACKAGE wrapped PEK.
 *
 * Production binds Task Profile establishment keys; Q5 harness supplies test keys only.
 */
fun interface Profile01RecipientKeyEstablishmentSeam {
    fun unwrapPek(
        wrappedPek: ByteArray,
        recipientModuleId: String,
        recipientKeyVersion: Long,
    ): Profile01PekUnwrapResult
}

sealed class Profile01PekUnwrapResult {
    data class Ready(val pek: ByteArray) : Profile01PekUnwrapResult()

    data class Rejected(val reason: String) : Profile01PekUnwrapResult()
}
