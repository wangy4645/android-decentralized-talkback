package com.talkback.appprod.enrollment

import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentEnrollmentEvidenceProducer
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentEnrollmentEvidenceProduceResult
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult
import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot

/**
 * PR-1 / E1: create or reuse local Android Keystore establishment identity.
 */
class EstablishmentLocalIdentityEnsurer(
    private val keystoreStore: LocalEstablishmentKeystoreIdentityStore,
    private val establishmentKeyVersion: Long = EstablishmentEnrollmentPolicy.LAB_ESTABLISHMENT_KEY_VERSION,
) {
    sealed class Outcome {
        data class Ready(
            val identity: Profile01LocalEstablishmentIdentitySnapshot,
            val newlyCreated: Boolean,
        ) : Outcome()

        data class Failed(
            val code: String,
            val reason: String,
        ) : Outcome()
    }

    fun ensureLocalIdentity(moduleId: String): Outcome {
        val normalized = EstablishmentEnrollmentPolicy.normalizeModuleId(moduleId)
        if (normalized.isBlank()) {
            return Outcome.Failed("E_KEYSTORE_PROVISION_FAILED", "moduleId required")
        }
        return when (
            val provision =
                keystoreStore.provisionFirstIdentity(normalized, establishmentKeyVersion)
        ) {
            is LocalEstablishmentKeystoreProvisionResult.Created ->
                verifyEvidence(provision.identity, newlyCreated = true)
            is LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned ->
                verifyEvidence(provision.identity, newlyCreated = false)
            is LocalEstablishmentKeystoreProvisionResult.Failed ->
                Outcome.Failed("E_KEYSTORE_PROVISION_FAILED", provision.reason)
        }
    }

    private fun verifyEvidence(
        identity: Profile01LocalEstablishmentIdentitySnapshot,
        newlyCreated: Boolean,
    ): Outcome {
        when (
            val evidence =
                EstablishmentEnrollmentEvidenceProducer.produce(
                    identity,
                    localKeyGeneration = establishmentKeyVersion,
                )
        ) {
            is EstablishmentEnrollmentEvidenceProduceResult.Ready ->
                return Outcome.Ready(identity, newlyCreated)
            is EstablishmentEnrollmentEvidenceProduceResult.Rejected ->
                return Outcome.Failed("E_KEYSTORE_PROVISION_FAILED", evidence.reason)
        }
    }
}
