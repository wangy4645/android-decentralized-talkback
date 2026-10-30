package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.profile.ProfileAuthenticationResult

sealed class EstablishmentProfileRevisionDeliveryAcceptResult {
    data class Accepted(
        val state: AcceptedLocalEstablishmentTrustState,
        val idempotent: Boolean,
    ) : EstablishmentProfileRevisionDeliveryAcceptResult()

    data class AuthenticationRejected(
        val reason: String,
    ) : EstablishmentProfileRevisionDeliveryAcceptResult()

    data object RollbackRejected : EstablishmentProfileRevisionDeliveryAcceptResult()

    data object RevisionConflictRejected : EstablishmentProfileRevisionDeliveryAcceptResult()

    data class SemanticRejected(
        val reason: String,
    ) : EstablishmentProfileRevisionDeliveryAcceptResult()

    data class PersistenceFailed(
        val reason: String,
    ) : EstablishmentProfileRevisionDeliveryAcceptResult()
}

/**
 * PAP-gated establishment profile delivery acceptance (EP production wiring).
 *
 * Does not provision keys, enroll devices, or mutate Keystore.
 */
class EstablishmentProfileRevisionDeliveryAcceptor(
    private val authenticator: EstablishmentProfileOperationalAuthenticator,
    private val acceptor: EstablishmentProfileRevisionAcceptor,
) {
    fun acceptSignedDelivery(candidateBytes: ByteArray): EstablishmentProfileRevisionDeliveryAcceptResult {
        when (val auth = authenticator.authenticate(candidateBytes)) {
            is ProfileAuthenticationResult.Rejected ->
                return EstablishmentProfileRevisionDeliveryAcceptResult.AuthenticationRejected(auth.reason)
            ProfileAuthenticationResult.Authenticated -> Unit
        }
        val revision =
            authenticator.authenticatedRevision(candidateBytes)
                ?: return EstablishmentProfileRevisionDeliveryAcceptResult.AuthenticationRejected(
                    "establishment profile semantics rejected",
                )
        return when (val accepted = acceptor.acceptAuthenticatedRevision(revision)) {
            is EstablishmentProfileRevisionAcceptResult.Accepted ->
                EstablishmentProfileRevisionDeliveryAcceptResult.Accepted(
                    accepted.state,
                    accepted.idempotent,
                )
            EstablishmentProfileRevisionAcceptResult.RollbackRejected ->
                EstablishmentProfileRevisionDeliveryAcceptResult.RollbackRejected
            EstablishmentProfileRevisionAcceptResult.RevisionConflictRejected ->
                EstablishmentProfileRevisionDeliveryAcceptResult.RevisionConflictRejected
            is EstablishmentProfileRevisionAcceptResult.SemanticRejected ->
                EstablishmentProfileRevisionDeliveryAcceptResult.SemanticRejected(accepted.reason)
            is EstablishmentProfileRevisionAcceptResult.PersistenceFailed ->
                EstablishmentProfileRevisionDeliveryAcceptResult.PersistenceFailed(accepted.reason)
        }
    }
}
