package com.talkback.appprod.enrollment

import com.talkback.core.conference.session.profile01.wire.Profile01LocalEstablishmentIdentityAvailability
import com.talkback.core.conference.session.profile01.wire.Profile01VerifiedLocalEstablishmentIdentity
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore

/**
 * PR-3 / E4: verify accepted profile + Keystore possession after reload.
 */
object EstablishmentEnrollmentLocalVerify {
    fun evaluateLocalReadiness(
        moduleId: String,
        establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
        keystoreStore: LocalEstablishmentKeystoreIdentityStore,
    ): LocalEstablishmentReadiness {
        val normalized = EstablishmentEnrollmentPolicy.normalizeModuleId(moduleId)
        return when (
            val availability =
                Profile01VerifiedLocalEstablishmentIdentity.resolve(
                    establishmentStore = establishmentStore,
                    keystoreStore = keystoreStore,
                    localModuleId = normalized,
                )
        ) {
            is Profile01LocalEstablishmentIdentityAvailability.Verified ->
                LocalEstablishmentReadiness(
                    ready = true,
                    reasonCode = null,
                    detail = "Verified establishmentKeyVersion=${availability.identity.establishmentKeyVersion}",
                )
            is Profile01LocalEstablishmentIdentityAvailability.Unavailable ->
                LocalEstablishmentReadiness(
                    ready = false,
                    reasonCode = mapUnavailable(availability.diagnostic),
                    detail = availability.diagnostic,
                )
        }
    }

    private fun mapUnavailable(reason: String): String =
        when {
            reason.contains("MISSING_ESTABLISHMENT_PROFILE", ignoreCase = true) ->
                "E_AWAITING_ENROLLMENT"
            reason.contains("MISSING_LOCAL_ESTABLISHMENT_BINDING", ignoreCase = true) ->
                "E_AWAITING_ENROLLMENT"
            else -> "E_LOCAL_VERIFY_FAILED"
        }
}

data class LocalEstablishmentReadiness(
    val ready: Boolean,
    val reasonCode: String?,
    val detail: String,
)

data class ConferenceEstablishmentReadiness(
    val ready: Boolean,
    val missingPeerModuleIds: List<String>,
    val reasonCode: String?,
)
