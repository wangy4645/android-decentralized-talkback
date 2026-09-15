package com.talkback.core.session.gbc.wiring

import com.talkback.core.session.gbc.crypto.ProductionGenerationFactVerifier
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.FileAcceptedTrustStatePersistence
import com.talkback.core.session.gbc.trust.profile.ProductionProfileRevisionAuthenticator
import com.talkback.core.session.gbc.trust.profile.ProfileBackedGenerationFactTrustLookup
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptor
import com.talkback.core.session.gbc.trust.profile.establishment.FileOperationalTrustAnchorPersistence
import com.talkback.core.session.gbc.trust.profile.establishment.OperationalTrustAnchorBootstrap
import com.talkback.core.session.gbc.trust.profile.establishment.OperationalTrustAnchorPersistence
import com.talkback.core.session.gbc.trust.profile.establishment.PersistedOperationalTrustAnchorSource
import java.nio.file.Path

/**
 * ADR-0057 Production Verifier Wiring composition root (PW-IA-T1..T3).
 *
 * Trust-not-ready → [ProductionGenerationFactVerifier] returns UNVERIFIABLE via empty lookup;
 * no injectable fallback and no startup readiness state machine.
 */
object GbcProductionTrustComposition {
    const val DEFAULT_DEPLOYMENT_TRUST_DOMAIN = "talkback-operational"

    data class GbcProductionTrustRuntime(
        val deploymentTrustDomainId: String,
        val anchorPersistence: OperationalTrustAnchorPersistence,
        val anchorSource: PersistedOperationalTrustAnchorSource,
        val profileAcceptor: ProfileRevisionAcceptor,
        val trustStore: AcceptedLocalTrustStateStore,
        val trustLookup: ProfileBackedGenerationFactTrustLookup,
        val verifier: ProductionGenerationFactVerifier,
    )

    fun create(
        deploymentTrustDomainId: String,
        anchorFile: Path,
        trustStateFile: Path,
    ): GbcProductionTrustRuntime {
        require(deploymentTrustDomainId.isNotBlank()) { "deploymentTrustDomainId required" }
        val anchorPersistence = FileOperationalTrustAnchorPersistence(anchorFile)
        val anchorSource = OperationalTrustAnchorBootstrap.loadSource(anchorPersistence)
        val authenticator =
            ProductionProfileRevisionAuthenticator(anchorSource, deploymentTrustDomainId)
        val trustPersistence = FileAcceptedTrustStatePersistence(trustStateFile)
        val trustStore = AcceptedLocalTrustStateStore(trustPersistence)
        val profileAcceptor = ProfileRevisionAcceptor(authenticator, trustStore)
        val trustLookup = ProfileBackedGenerationFactTrustLookup(trustStore)
        val verifier = ProductionGenerationFactVerifier(trustLookup)
        return GbcProductionTrustRuntime(
            deploymentTrustDomainId = deploymentTrustDomainId,
            anchorPersistence = anchorPersistence,
            anchorSource = anchorSource,
            profileAcceptor = profileAcceptor,
            trustStore = trustStore,
            trustLookup = trustLookup,
            verifier = verifier,
        )
    }

    fun toProductionWiring(
        runtime: GbcProductionTrustRuntime,
        originRuntime: GbcOriginComposition.GbcOriginRuntime? = null,
    ): ProductionGbcTrustWiring = ProductionGbcTrustWiring(runtime, originRuntime)

    fun createProductionWiring(
        deploymentTrustDomainId: String,
        anchorFile: Path,
        trustStateFile: Path,
        originRuntime: GbcOriginComposition.GbcOriginRuntime? = null,
    ): ProductionGbcTrustWiring =
        toProductionWiring(create(deploymentTrustDomainId, anchorFile, trustStateFile), originRuntime)
}
