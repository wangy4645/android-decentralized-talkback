package com.talkback.core.session.gbc.wiring

import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentFieldProbe
import java.nio.file.Path

/**
 * Production read-only probe session factory for EP1–EP5 field gate.
 */
object EstablishmentFieldProbeRuntime {
    fun fromProductionTrustDir(
        deploymentTrustDomainId: String,
        trustDir: Path,
        localModuleId: String,
    ): EstablishmentFieldProbe.Session {
        val anchorFile = trustDir.resolve("operational-trust-anchor.bin")
        val signingStateFile = trustDir.resolve("accepted-local-trust-state.bin")
        val establishmentStateFile = trustDir.resolve("accepted-local-establishment-trust-state.bin")
        val gbcRuntime =
            GbcProductionTrustComposition.create(
                deploymentTrustDomainId = deploymentTrustDomainId,
                anchorFile = anchorFile,
                trustStateFile = signingStateFile,
            )
        val establishmentRuntime =
            EstablishmentProductionComposition.create(
                deploymentTrustDomainId = deploymentTrustDomainId,
                anchorSource = gbcRuntime.anchorSource,
                localModuleId = localModuleId,
                establishmentStateFile = establishmentStateFile,
            )
        return EstablishmentFieldProbe.Session(
            deviceModuleId = localModuleId,
            establishmentAuthority = establishmentRuntime.authoritySurface,
            signingTrustStore = gbcRuntime.trustStore,
        )
    }
}
