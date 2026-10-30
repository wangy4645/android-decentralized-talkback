package com.talkback.core.session.gbc.wiring

import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentResult
import com.talkback.core.session.gbc.trust.profile.establishment.OperationalEstablishmentFixtureSupport
import java.nio.file.Files
import java.nio.file.Path

/**
 * PW-EG harness support — persisted anchor through production composition root.
 */
object ProductionVerifierWiringFixtureSupport {
    const val TRUST_DOMAIN = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN

    fun newPersistedHarness(): PersistedHarness {
        val dir = Files.createTempDirectory("pw-wiring")
        val anchorFile = dir.resolve("operational-trust-anchor.bin")
        val trustStateFile = dir.resolve("accepted-local-trust-state.bin")
        return persistedHarness(anchorFile, trustStateFile)
    }

    fun persistedHarness(
        anchorFile: Path,
        trustStateFile: Path,
    ): PersistedHarness {
        val oeHarness = OperationalEstablishmentFixtureSupport.productHarness(anchorFile)
        when (val established = OperationalEstablishmentFixtureSupport.establishProductAnchor(oeHarness)) {
            is EstablishmentResult.Established,
            is EstablishmentResult.AlreadyEstablished,
            -> Unit
            is EstablishmentResult.Rejected ->
                error("anchor establishment failed: ${established.reason}")
        }
        val runtime =
            GbcProductionTrustComposition.create(
                deploymentTrustDomainId = TRUST_DOMAIN,
                anchorFile = anchorFile,
                trustStateFile = trustStateFile,
            )
        val wiring = GbcProductionTrustComposition.toProductionWiring(runtime)
        return PersistedHarness(anchorFile, trustStateFile, runtime, wiring)
    }

    data class PersistedHarness(
        val anchorFile: Path,
        val trustStateFile: Path,
        val runtime: GbcProductionTrustComposition.GbcProductionTrustRuntime,
        val productionWiring: ProductionGbcTrustWiring,
    )
}
