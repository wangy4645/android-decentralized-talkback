package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.conference.session.profile01.Profile01ModuleTrustLookupResult
import com.talkback.core.conference.session.profile01.Profile01ProfileBackedModuleTrustLookup
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningTrustModuleMaterializationSupportTest {
    @Test
    fun detectNewlyMaterializedPeerModules_whenM04BindingAdded() {
        val previous =
            AcceptedLocalTrustState(
                taskProfileRevision = 10L,
                revisionIdentity = byteArrayOf(1),
                localModuleId = "M01",
                bindingsByKey =
                    mapOf(
                        binding("M02", 1L),
                        binding("M03", 1L),
                    ),
            )
        val next =
            previous.copy(
                taskProfileRevision = 11L,
                revisionIdentity = byteArrayOf(2),
                bindingsByKey = previous.bindingsByKey + binding("M04", 1L),
            )

        val materialized =
            SigningTrustModuleMaterializationSupport.detectNewlyMaterializedPeerModules(
                previous = previous,
                next = next,
            )

        assertEquals(setOf("M04"), materialized)
    }

    @Test
    fun activeSigningVerificationOnSnapshot_findsActiveBinding() {
        val snapshot =
            AcceptedLocalTrustState(
                taskProfileRevision = 10L,
                revisionIdentity = byteArrayOf(1),
                localModuleId = "M01",
                bindingsByKey = mapOf(binding("M04", 1L)),
            )

        val lookup =
            Profile01ProfileBackedModuleTrustLookup.activeSigningVerificationOnSnapshot(
                snapshot,
                "M04",
            )

        assertTrue(lookup is Profile01ModuleTrustLookupResult.Found)
    }

    private fun binding(
        moduleId: String,
        version: Long,
    ): Pair<AcceptedLocalTrustState.BindingKey, ModuleSigningBinding> {
        val spki = ByteArray(65) { (it + moduleId.hashCode()).toByte() }
        val key = AcceptedLocalTrustState.BindingKey(moduleId, version)
        return key to
            ModuleSigningBinding(
                moduleId = moduleId,
                signerKeyVersion = version,
                keyState = GenerationFactKeyState.ACTIVE,
                publicKeySpki = spki,
                activatedAtRevision = 10L,
            )
    }
}
