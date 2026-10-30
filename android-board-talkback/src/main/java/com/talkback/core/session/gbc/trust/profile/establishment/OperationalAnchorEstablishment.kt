package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.profile.OperationalTrustAnchor

/**
 * PAP-T2 establishment provenance class (form deferred beyond seam identity).
 */
enum class EstablishmentProvenanceClass {
    OPERATOR_OUT_OF_BAND,
    MANAGED_INSTALLATION,
    AUTHENTICATED_MANUFACTURING,
}

/**
 * Input to PAP-T2 establishment authorization — not self-authorizing.
 */
data class EstablishmentRecord(
    val deploymentTrustDomainId: String,
    val operationalAuthorityPublicKeySpki: ByteArray,
    val establishmentRecordIdentity: String,
    val provenanceClass: EstablishmentProvenanceClass,
) {
    init {
        require(deploymentTrustDomainId.isNotBlank()) { "deploymentTrustDomainId required" }
        require(operationalAuthorityPublicKeySpki.isNotEmpty()) { "operationalAuthorityPublicKeySpki required" }
        require(establishmentRecordIdentity.isNotBlank()) { "establishmentRecordIdentity required" }
    }

    fun toAnchor(): OperationalTrustAnchor =
        OperationalTrustAnchor(
            deploymentTrustDomainId = deploymentTrustDomainId,
            operationalAuthorityPublicKeySpki = operationalAuthorityPublicKeySpki.copyOf(),
        )
}

/**
 * OE-IA-T1: one-time establishment capability bound to a validated record.
 * MUST NOT be constructible by ordinary runtime callers.
 */
class EstablishmentAuthorization internal constructor(
    internal val boundRecord: EstablishmentRecord,
)

/**
 * PAP-T2-conformant establishment source — sole issuer of [EstablishmentAuthorization].
 */
class PapT2EstablishmentAuthorizationIssuer {
    fun authorize(record: EstablishmentRecord): EstablishmentAuthorization? {
        if (record.deploymentTrustDomainId.isBlank()) return null
        if (record.operationalAuthorityPublicKeySpki.isEmpty()) return null
        if (record.establishmentRecordIdentity.isBlank()) return null
        return EstablishmentAuthorization(record)
    }
}

sealed class EstablishmentResult {
    data class Established(
        val anchor: OperationalTrustAnchor,
    ) : EstablishmentResult()

    data class AlreadyEstablished(
        val anchor: OperationalTrustAnchor,
    ) : EstablishmentResult()

    data class Rejected(
        val reason: String,
    ) : EstablishmentResult()
}

/**
 * Device-side first-anchor establishment (OE-1..OE-5).
 *
 * OE-IA-T1: requires [EstablishmentAuthorization] from [PapT2EstablishmentAuthorizationIssuer].
 * OE-IA-T2: delegates create-once commit to persistence.
 */
class OperationalAnchorEstablisher(
    private val persistence: OperationalTrustAnchorPersistence,
) {
    fun establish(authorization: EstablishmentAuthorization): EstablishmentResult {
        val record = authorization.boundRecord
        val anchor = record.toAnchor()
        return when (
            val commit =
                persistence.establishFirstAnchor(
                    anchor = anchor,
                    establishmentRecordIdentity = record.establishmentRecordIdentity,
                )
        ) {
            is EstablishmentCommitResult.Committed ->
                EstablishmentResult.Established(commit.anchor)
            is EstablishmentCommitResult.AlreadyEstablished ->
                EstablishmentResult.AlreadyEstablished(commit.anchor)
            is EstablishmentCommitResult.Rejected ->
                EstablishmentResult.Rejected(commit.reason)
        }
    }
}

sealed class EstablishmentCommitResult {
    data class Committed(
        val anchor: OperationalTrustAnchor,
    ) : EstablishmentCommitResult()

    data class AlreadyEstablished(
        val anchor: OperationalTrustAnchor,
    ) : EstablishmentCommitResult()

    data class Rejected(
        val reason: String,
    ) : EstablishmentCommitResult()
}

sealed class AnchorPersistenceState {
    data object Empty : AnchorPersistenceState()

    data class Established(
        val anchor: OperationalTrustAnchor,
        val establishmentRecordIdentity: String,
    ) : AnchorPersistenceState()
}

/**
 * Create-once durable operational anchor store (OE-IA-T2 / OE-IA-T3).
 */
interface OperationalTrustAnchorPersistence {
    fun currentState(): AnchorPersistenceState

    fun establishFirstAnchor(
        anchor: OperationalTrustAnchor,
        establishmentRecordIdentity: String,
    ): EstablishmentCommitResult
}

/**
 * Read-only product load path (OE-6).
 */
class PersistedOperationalTrustAnchorSource(
    private val persistence: OperationalTrustAnchorPersistence,
) : com.talkback.core.session.gbc.trust.profile.OperationalTrustAnchorSource {
    override fun pinnedAnchor(deploymentTrustDomainId: String): com.talkback.core.session.gbc.trust.profile.OperationalTrustAnchor? {
        return when (val state = persistence.currentState()) {
            AnchorPersistenceState.Empty -> null
            is AnchorPersistenceState.Established -> {
                if (state.anchor.deploymentTrustDomainId != deploymentTrustDomainId) {
                    null
                } else {
                    state.anchor.copy(
                        operationalAuthorityPublicKeySpki =
                            state.anchor.operationalAuthorityPublicKeySpki.copyOf(),
                    )
                }
            }
        }
    }
}

object OperationalTrustAnchorBootstrap {
    fun loadSource(persistence: OperationalTrustAnchorPersistence): PersistedOperationalTrustAnchorSource =
        PersistedOperationalTrustAnchorSource(persistence)
}
