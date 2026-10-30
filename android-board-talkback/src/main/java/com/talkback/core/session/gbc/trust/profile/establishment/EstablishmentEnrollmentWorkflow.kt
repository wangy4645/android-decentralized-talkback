package com.talkback.core.session.gbc.trust.profile.establishment

/**
 * End-to-end enrollment workflow orchestration (PR-EP-3).
 */
object EstablishmentEnrollmentWorkflow {
    data class VerifiedEnrollment(
        val draft: EstablishmentEnrollmentRevisionDraft,
        val acceptedState: AcceptedLocalEstablishmentTrustState,
    )

    sealed class EnrollmentWorkflowResult {
        data class Verified(
            val value: VerifiedEnrollment,
        ) : EnrollmentWorkflowResult()

        data class Rejected(
            val stage: String,
            val reason: String,
        ) : EnrollmentWorkflowResult()
    }

    fun publishAcceptAndVerify(
        keystoreStore: LocalEstablishmentKeystoreIdentityStore,
        establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
        authenticator: EstablishmentProfileOperationalAuthenticator,
        publicationSeam: EstablishmentProfilePublicationSeam,
        authorityDraft: EstablishmentEnrollmentRevisionDraft,
    ): EnrollmentWorkflowResult {
        val payload = EstablishmentEnrollmentAuthorityHelper.draftToPayload(authorityDraft)
        val protected = EstablishmentProfileRevisionCanonicalCodec.encode(payload)
        val signedDelivery = publicationSeam.publishProtectedSemantics(protected)
        val revision =
            authenticator.authenticatedRevision(signedDelivery)
                ?: return EnrollmentWorkflowResult.Rejected("pap", "authentication failed")
        val acceptor = EstablishmentProfileRevisionAcceptor(establishmentStore)
        when (val accepted = acceptor.acceptAuthenticatedRevision(revision)) {
            is EstablishmentProfileRevisionAcceptResult.RollbackRejected ->
                return EnrollmentWorkflowResult.Rejected("acceptor", "rollback rejected")
            is EstablishmentProfileRevisionAcceptResult.RevisionConflictRejected ->
                return EnrollmentWorkflowResult.Rejected("acceptor", "revision conflict")
            is EstablishmentProfileRevisionAcceptResult.SemanticRejected ->
                return EnrollmentWorkflowResult.Rejected("acceptor", accepted.reason)
            is EstablishmentProfileRevisionAcceptResult.PersistenceFailed ->
                return EnrollmentWorkflowResult.Rejected("acceptor", accepted.reason)
            is EstablishmentProfileRevisionAcceptResult.Accepted -> {
                val verify =
                    LocalEstablishmentBindingVerifier.verifyRevisionAgainstKeystore(
                        revision,
                        keystoreStore,
                    )
                if (verify !is LocalEstablishmentBindingVerifyResult.Verified) {
                    return EnrollmentWorkflowResult.Rejected("verifier", verify.toString())
                }
                return EnrollmentWorkflowResult.Verified(
                    VerifiedEnrollment(
                        draft = authorityDraft,
                        acceptedState = accepted.state,
                    ),
                )
            }
        }
    }
}
