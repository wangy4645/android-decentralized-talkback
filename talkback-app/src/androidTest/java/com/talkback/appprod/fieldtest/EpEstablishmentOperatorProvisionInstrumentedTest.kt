package com.talkback.appprod.fieldtest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpEstablishmentOperatorProvisionInstrumentedTest {
    private fun targetContext() =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun args() = InstrumentationRegistry.getArguments()

    @Test
    fun provisionLocalEstablishmentIdentity() {
        val version =
            args().getString("establishmentKeyVersion")?.toLongOrNull()
                ?: error("establishmentKeyVersion required")
        val result =
            EpEstablishmentOperatorProvisionSupport.provisionKeystoreAndEmitEvidence(
                targetContext(),
                version,
            )
        assertTrue(result.message, result.success)
    }

    @Test
    fun writeSignedEstablishmentDeliveryFromBase64() {
        val delivery =
            args().getString("signedDeliveryBase64")
                ?: error("signedDeliveryBase64 required")
        val result =
            EpEstablishmentOperatorProvisionSupport.writeSignedEstablishmentDeliveryFromBase64(
                targetContext(),
                delivery,
            )
        assertTrue(result.message, result.success)
    }

    @Test
    fun acceptEstablishmentRevision() {
        val result =
            EpEstablishmentOperatorProvisionSupport.acceptEstablishmentRevision(targetContext())
        assertTrue(result.message, result.success)
    }

    @Test
    fun probeEstablishmentStorePresent() {
        val result =
            EpEstablishmentOperatorProvisionSupport.probeEstablishmentStorePresent(targetContext())
        assertTrue(result.moduleId.isNotBlank())
    }

    @Test
    fun verifyLocalKeystoreIdentity() {
        val version =
            args().getString("establishmentKeyVersion")?.toLongOrNull()
                ?: error("establishmentKeyVersion required")
        val fingerprint =
            args().getString("expectedFingerprint")
                ?: error("expectedFingerprint required")
        val result =
            EpEstablishmentOperatorProvisionSupport.verifyLocalKeystoreIdentity(
                targetContext(),
                version,
                fingerprint,
            )
        assertTrue(result.message, result.success)
    }

    @Test
    fun pre4RemoteLookup() {
        val version =
            args().getString("establishmentKeyVersion")?.toLongOrNull()
                ?: error("establishmentKeyVersion required")
        val peer =
            args().getString("peerModuleId")
                ?: error("peerModuleId required")
        val result =
            EpEstablishmentOperatorProvisionSupport.pre4RemoteLookup(
                targetContext(),
                peer,
                version,
            )
        assertTrue(result.message, result.success)
    }
}
