package com.talkback.appprod.enrollment

import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionDeliveryAcceptResult
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionDeliveryAcceptor

/**
 * PR-2 / E2–E3: accept signed establishment profile delivery via existing PAP acceptor.
 */
class EstablishmentEnrollmentAcceptor(
    private val deliveryAcceptor: EstablishmentProfileRevisionDeliveryAcceptor,
) {
    sealed class Outcome {
        data class Accepted(
            val taskProfileRevision: Long,
            val idempotent: Boolean,
        ) : Outcome()

        data class Rejected(
            val code: String,
            val reason: String,
        ) : Outcome()
    }

    fun acceptSignedDelivery(signedDeliveryBytes: ByteArray): Outcome {
        if (signedDeliveryBytes.isEmpty()) {
            return Outcome.Rejected("E_ENROLL_SEMANTIC_REJECTED", "empty delivery")
        }
        return when (val result = deliveryAcceptor.acceptSignedDelivery(signedDeliveryBytes)) {
            is EstablishmentProfileRevisionDeliveryAcceptResult.Accepted ->
                Outcome.Accepted(
                    taskProfileRevision = result.state.taskProfileRevision,
                    idempotent = result.idempotent,
                )
            is EstablishmentProfileRevisionDeliveryAcceptResult.AuthenticationRejected ->
                Outcome.Rejected("E_ENROLL_AUTH_REJECTED", result.reason)
            EstablishmentProfileRevisionDeliveryAcceptResult.RollbackRejected ->
                Outcome.Rejected("E_ENROLL_AUTH_REJECTED", "rollback rejected")
            EstablishmentProfileRevisionDeliveryAcceptResult.RevisionConflictRejected ->
                Outcome.Rejected("E_ENROLL_REVISION_CONFLICT", "revision conflict")
            is EstablishmentProfileRevisionDeliveryAcceptResult.SemanticRejected ->
                Outcome.Rejected("E_ENROLL_SEMANTIC_REJECTED", result.reason)
            is EstablishmentProfileRevisionDeliveryAcceptResult.PersistenceFailed ->
                Outcome.Rejected("E_ENROLL_PERSIST_FAILED", result.reason)
        }
    }
}
