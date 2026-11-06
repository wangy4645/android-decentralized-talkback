package com.talkback.appprod.enrollment

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.conference.session.profile01.wire.Profile01RecipientEstablishmentKeyLookup
import com.talkback.core.session.ConferenceTopologySnapshot
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore

class EstablishmentReadinessGate(
    private val moduleId: String,
    private val establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
    private val keystoreStore: LocalEstablishmentKeystoreIdentityStore,
    private val peerLookup: Profile01RecipientEstablishmentKeyLookup,
) {
    @Volatile
    private var localCache: LocalEstablishmentReadiness? = null

    fun invalidateLocal() {
        localCache = null
        establishmentStore.reloadFromPersistence()
    }

    fun invalidateConference(@Suppress("UNUSED_PARAMETER") sessionId: String) = Unit

    fun localReadiness(): LocalEstablishmentReadiness {
        localCache?.let { return it }
        val evaluated =
            EstablishmentEnrollmentLocalVerify.evaluateLocalReadiness(
                moduleId = moduleId,
                establishmentStore = establishmentStore,
                keystoreStore = keystoreStore,
            )
        if (evaluated.ready) {
            localCache = evaluated
        }
        return evaluated
    }

    fun conferenceReadiness(
        snapshot: ConferenceTopologySnapshot,
        peerModuleIds: Collection<String>,
    ): ConferenceEstablishmentReadiness {
        // snapshot reserved for Phase 3 roster/session generation checks
        if (snapshot.conferenceId.isBlank()) {
            return ConferenceEstablishmentReadiness(
                ready = false,
                missingPeerModuleIds = emptyList(),
                reasonCode = "R_CONFERENCE_PEER_MISSING",
            )
        }
        val local = localReadiness()
        if (!local.ready) {
            return ConferenceEstablishmentReadiness(
                ready = false,
                missingPeerModuleIds = emptyList(),
                reasonCode = local.reasonCode ?: "R_LOCAL_NOT_READY",
            )
        }
        val normalizedLocal = EstablishmentEnrollmentPolicy.normalizeModuleId(moduleId)
        val targets =
            peerModuleIds
                .map { EstablishmentEnrollmentPolicy.normalizeModuleId(it) }
                .filter { it.isNotBlank() && it != normalizedLocal }
                .distinct()
        if (targets.isEmpty()) {
            return ConferenceEstablishmentReadiness(ready = true, missingPeerModuleIds = emptyList(), reasonCode = null)
        }
        val missing = mutableListOf<String>()
        for (peer in targets) {
            when (peerLookup.activeEstablishmentKey(peer)) {
                is Profile01EstablishmentKeyLookupResult.Found -> Unit
                else -> missing.add(peer)
            }
        }
        return if (missing.isEmpty()) {
            ConferenceEstablishmentReadiness(ready = true, missingPeerModuleIds = emptyList(), reasonCode = null)
        } else {
            ConferenceEstablishmentReadiness(
                ready = false,
                missingPeerModuleIds = missing,
                reasonCode = "R_CONFERENCE_PEER_MISSING",
            )
        }
    }
}
