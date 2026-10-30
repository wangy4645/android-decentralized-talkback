package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import java.security.KeyPairGenerator

object EstablishmentProfileRevisionFixtureSupport {
    const val LOCAL_MODULE_ID = "M01"
    const val PEER_MODULE_ID = "M02"
    const val ESTABLISHMENT_KEY_VERSION = 4L
    const val ACTIVE_REVISION = 10L

    val peerEstablishmentSpki: ByteArray =
        Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki()

    fun harness(): Harness {
        val store = AcceptedLocalEstablishmentTrustStateStore()
        val acceptor = EstablishmentProfileRevisionAcceptor(store)
        return Harness(store, acceptor)
    }

    fun peerBinding(
        moduleId: String = PEER_MODULE_ID,
        establishmentKeyVersion: Long = ESTABLISHMENT_KEY_VERSION,
        keyState: GenerationFactKeyState = GenerationFactKeyState.ACTIVE,
        establishmentPublicKeySpki: ByteArray = peerEstablishmentSpki,
        activatedAtRevision: Long = ACTIVE_REVISION,
    ): EstablishmentProfileBinding =
        EstablishmentProfileBinding(
            moduleId = moduleId,
            establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
            establishmentKeyVersion = establishmentKeyVersion,
            keyState = keyState,
            establishmentPublicKeySpki = establishmentPublicKeySpki.copyOf(),
            activatedAtRevision = activatedAtRevision,
        )

    fun localBinding(
        establishmentKeyVersion: Long = ESTABLISHMENT_KEY_VERSION,
        establishmentPublicKeySpki: ByteArray = peerEstablishmentSpki,
    ): EstablishmentProfileBinding = peerBinding(LOCAL_MODULE_ID, establishmentKeyVersion, establishmentPublicKeySpki = establishmentPublicKeySpki)

    fun duoPayload(
        revision: Long = ACTIVE_REVISION,
        peerVersion: Long = ESTABLISHMENT_KEY_VERSION,
        peerSpki: ByteArray = peerEstablishmentSpki,
    ): EstablishmentProfileTrustPayload =
        EstablishmentProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = LOCAL_MODULE_ID,
            establishmentBindings =
                listOf(
                    localBinding(establishmentKeyVersion = peerVersion, establishmentPublicKeySpki = peerSpki),
                    peerBinding(establishmentKeyVersion = peerVersion, establishmentPublicKeySpki = peerSpki),
                ),
        )

    fun authenticated(
        payload: EstablishmentProfileTrustPayload,
    ): AuthenticatedEstablishmentProfileRevision =
        AuthenticatedEstablishmentProfileRevision.forValidatedSemantics(payload)

    fun accept(
        harness: Harness,
        payload: EstablishmentProfileTrustPayload,
    ): EstablishmentProfileRevisionAcceptResult =
        harness.acceptor.acceptAuthenticatedRevision(authenticated(payload))

    fun generateRsa3072Spki(): ByteArray {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        return keyPair.public.encoded
    }

    data class Harness(
        val store: AcceptedLocalEstablishmentTrustStateStore,
        val acceptor: EstablishmentProfileRevisionAcceptor,
    )
}
