package com.talkback.core.session.gbc.trust.mesh

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding
import com.talkback.core.session.gbc.trust.profile.FileAcceptedTrustStatePersistence
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionIdentity
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentRevisionIdentity
import com.talkback.core.session.gbc.trust.profile.establishment.FileAcceptedEstablishmentTrustStatePersistence
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding
import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshHelloPeerTrustSupplementTest {
    private val domain = "field-test-domain"
    private val sharedSpki = byteArrayOf(1, 2, 3, 4)
    private val m05EstablishmentSpki = byteArrayOf(9, 8, 7)

    @Test
    fun homogeneousSigningAndHelloEstablishment_materializesLatePeer_withFilePersistence() {
        val trustDir = Files.createTempDirectory("mesh-trust-supplement")
        val signingStore =
            AcceptedLocalTrustStateStore(
                FileAcceptedTrustStatePersistence(trustDir.resolve("signing.bin")),
            )
        signingStore.atomicReplace(
            AcceptedLocalTrustState(
                taskProfileRevision = 10L,
                revisionIdentity = ProfileRevisionIdentity.fromProtectedBytes(byteArrayOf(10)),
                localModuleId = "M01",
                deploymentTrustDomainId = domain,
                bindingsByKey =
                    listOf("M01", "M02", "M03", "M04").map { moduleId ->
                        ModuleSigningBinding(
                            moduleId = moduleId,
                            signerKeyVersion = 1L,
                            keyState = GenerationFactKeyState.ACTIVE,
                            publicKeySpki = sharedSpki.copyOf(),
                            activatedAtRevision = 10L,
                        )
                    }.associateBy { AcceptedLocalTrustState.BindingKey(it.moduleId, it.signerKeyVersion) },
            ),
        )
        val establishmentStore =
            AcceptedLocalEstablishmentTrustStateStore(
                FileAcceptedEstablishmentTrustStatePersistence(trustDir.resolve("establishment.bin")),
            )
        establishmentStore.atomicReplace(
            AcceptedLocalEstablishmentTrustState(
                taskProfileRevision = 5L,
                revisionIdentity = EstablishmentRevisionIdentity.fromProtectedBytes(byteArrayOf(5)),
                localModuleId = "M01",
                deploymentTrustDomainId = domain,
                bindingsByKey =
                    mapOf(
                        AcceptedLocalEstablishmentTrustState.BindingKey("M01", 1L) to
                            ModuleEstablishmentBinding(
                                moduleId = "M01",
                                establishmentKeyVersion = 1L,
                                keyState = GenerationFactKeyState.ACTIVE,
                                establishmentPublicKeySpki = byteArrayOf(5),
                                activatedAtRevision = 5L,
                            ),
                    ),
            ),
        )
        val supplement =
            MeshHelloPeerTrustSupplement(
                signingTrustStore = signingStore,
                establishmentTrustStore = establishmentStore,
                localModuleId = "M01",
                deploymentTrustDomainId = domain,
            )
        val digest =
            MeshPeerTrustHelloDigest(
                establishmentPublicKeySpki = m05EstablishmentSpki.copyOf(),
                establishmentKeyVersion = 2L,
            )
        val latePeer = "M06"
        val result = supplement.applyFromHello(latePeer, digest)
        assertTrue(result is MeshHelloPeerTrustSupplement.ApplyResult.Extended)
        val signingAfter = signingStore.currentSnapshot()
        val establishmentAfter = establishmentStore.currentSnapshot()
        assertNotNull(
            signingAfter?.bindingsByKey.values.firstOrNull {
                it.moduleId == latePeer && it.keyState == GenerationFactKeyState.ACTIVE
            },
        )
        assertNotNull(
            establishmentAfter?.bindingsByKey.values.firstOrNull {
                it.moduleId == latePeer && it.establishmentKeyVersion == 2L
            },
        )
        signingStore.reloadFromPersistence()
        establishmentStore.reloadFromPersistence()
        assertNotNull(signingStore.currentSnapshot()?.bindingsByKey.values.firstOrNull { it.moduleId == latePeer })
        assertNotNull(
            establishmentStore.currentSnapshot()?.bindingsByKey.values.firstOrNull { it.moduleId == latePeer },
        )
    }
}
