package com.talkback.core.session.gbc.wiring

import com.talkback.core.session.gbc.FactDeliveryOrchestrator
import com.talkback.core.session.gbc.GenerationFactVerifier
import com.talkback.core.session.gbc.GroupBootstrapConvergenceWiring
import com.talkback.core.session.gbc.InjectableGenerationFactVerifier
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.crypto.ProductionGenerationFactVerifier
import com.talkback.core.session.gbc.trust.profile.ProfileBackedGenerationFactTrustLookup
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptor

/**
 * ADR-0057 PW-IA-T2: production and test composition roots are separate types.
 * Production runtime MUST NOT reach injectable verifiers through this seam.
 */
sealed class GbcTrustWiring {
    abstract val verificationBoundary: VerificationBoundary

    open val gbcOriginRuntime: GbcOriginComposition.GbcOriginRuntime? = null

    fun factDeliveryOrchestrator(wiring: GroupBootstrapConvergenceWiring): FactDeliveryOrchestrator =
        FactDeliveryOrchestrator(wiring, verificationBoundary)

    fun coldStartOriginOrchestrator(
        wiring: GroupBootstrapConvergenceWiring,
        factDeliveryOrchestrator: FactDeliveryOrchestrator,
    ): com.talkback.core.session.gbc.origin.ColdStartOriginOrchestrator? =
        gbcOriginRuntime?.createOrchestrator(wiring, factDeliveryOrchestrator, verificationBoundary)
}

/**
 * PW-IA-T2 / PW-IA-T3: sole production authority path.
 */
class ProductionGbcTrustWiring internal constructor(
    val runtime: GbcProductionTrustComposition.GbcProductionTrustRuntime,
    originRuntime: GbcOriginComposition.GbcOriginRuntime? = null,
) : GbcTrustWiring() {
    override val verificationBoundary: VerificationBoundary =
        VerificationBoundary(runtime.verifier)

    override val gbcOriginRuntime: GbcOriginComposition.GbcOriginRuntime? = originRuntime

    val profileRevisionAcceptor: ProfileRevisionAcceptor = runtime.profileAcceptor

    init {
        require(runtime.verifier is ProductionGenerationFactVerifier) {
            "production wiring requires ProductionGenerationFactVerifier"
        }
        require(runtime.trustLookup is ProfileBackedGenerationFactTrustLookup) {
            "production wiring requires ProfileBackedGenerationFactTrustLookup"
        }
    }
}

/**
 * Harness / integration tests only — not a production runtime fallback (PW-IA-T2).
 */
class TestGbcTrustWiring(
    val injectableVerifier: InjectableGenerationFactVerifier = InjectableGenerationFactVerifier(),
    originRuntime: GbcOriginComposition.GbcOriginRuntime? = null,
) : GbcTrustWiring() {
    override val verificationBoundary: VerificationBoundary =
        VerificationBoundary(injectableVerifier)

    override val gbcOriginRuntime: GbcOriginComposition.GbcOriginRuntime? = originRuntime

    val verifier: GenerationFactVerifier = injectableVerifier
}
