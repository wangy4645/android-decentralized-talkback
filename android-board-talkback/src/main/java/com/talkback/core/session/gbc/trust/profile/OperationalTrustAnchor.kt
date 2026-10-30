package com.talkback.core.session.gbc.trust.profile

/**
 * PAP-T2-established operational trust anchor (read-only authentication input).
 *
 * PTI-B-IA-T1: This type carries pre-established anchor material only.
 * ProductionProfileRevisionAuthenticator MUST NOT create, replace, rotate,
 * or persist anchors as a side effect of Profile authentication.
 */
data class OperationalTrustAnchor(
    val deploymentTrustDomainId: String,
    val operationalAuthorityPublicKeySpki: ByteArray,
) {
    init {
        require(deploymentTrustDomainId.isNotBlank()) { "deploymentTrustDomainId required" }
        require(operationalAuthorityPublicKeySpki.isNotEmpty()) { "operationalAuthorityPublicKeySpki required" }
    }
}

/**
 * Read-only pinned anchor lookup (PTI-B-IA-T1 / PAP-T2).
 *
 * MUST NOT learn, import, or mutate anchors from candidate Profile deliveries.
 */
fun interface OperationalTrustAnchorSource {
    fun pinnedAnchor(deploymentTrustDomainId: String): OperationalTrustAnchor?
}

/**
 * Immutable store holding one pre-established anchor for a deployment trust domain.
 */
class PinnedOperationalTrustAnchorStore(
    private val anchor: OperationalTrustAnchor,
) : OperationalTrustAnchorSource {
    private val frozenAnchor = anchor.copy(operationalAuthorityPublicKeySpki = anchor.operationalAuthorityPublicKeySpki.copyOf())

    override fun pinnedAnchor(deploymentTrustDomainId: String): OperationalTrustAnchor? =
        if (deploymentTrustDomainId == frozenAnchor.deploymentTrustDomainId) {
            frozenAnchor.copy(operationalAuthorityPublicKeySpki = frozenAnchor.operationalAuthorityPublicKeySpki.copyOf())
        } else {
            null
        }

    fun establishedAnchor(): OperationalTrustAnchor =
        frozenAnchor.copy(operationalAuthorityPublicKeySpki = frozenAnchor.operationalAuthorityPublicKeySpki.copyOf())
}
