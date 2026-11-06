package com.talkback.appprod.enrollment

import android.content.Context
import com.talkback.core.session.gbc.trust.profile.establishment.AndroidKeystoreLocalEstablishmentIdentityStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.wiring.EstablishmentProductionComposition
import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition
import java.io.File

object EstablishmentEnrollmentRuntimeFactory {
    fun trustDir(context: Context): File =
        context.filesDir.resolve("gbc-trust").also { it.mkdirs() }

    fun keystoreStore(): LocalEstablishmentKeystoreIdentityStore =
        AndroidKeystoreLocalEstablishmentIdentityStore()

    fun createProductionRuntime(
        context: Context,
        moduleId: String,
        keystoreStore: LocalEstablishmentKeystoreIdentityStore = keystoreStore(),
    ): EstablishmentProductionComposition.EstablishmentProductionRuntime {
        val trustDir = trustDir(context)
        val normalized = EstablishmentEnrollmentPolicy.normalizeModuleId(moduleId)
        val gbcRuntime =
            GbcProductionTrustComposition.create(
                deploymentTrustDomainId = EstablishmentEnrollmentPolicy.deploymentTrustDomainId,
                anchorFile = trustDir.resolve("operational-trust-anchor.bin").toPath(),
                trustStateFile = trustDir.resolve("accepted-local-trust-state.bin").toPath(),
            )
        return EstablishmentProductionComposition.create(
            deploymentTrustDomainId = EstablishmentEnrollmentPolicy.deploymentTrustDomainId,
            anchorSource = gbcRuntime.anchorSource,
            localModuleId = normalized,
            establishmentStateFile =
                trustDir.resolve("accepted-local-establishment-trust-state.bin").toPath(),
            keystoreStore = keystoreStore,
        )
    }
}
