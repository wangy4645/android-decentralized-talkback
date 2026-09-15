package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.session.gbc.crypto.EcdsaP256Verifier
import com.talkback.core.session.gbc.crypto.GenerationFactTestSigning
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

class Adr0057FtphSignedProfileExportTest {
    @Test
    fun exportDeskSignedFtphProfiles() {
        val outDir = Paths.get("build", "ftph-signed-profiles")
        Files.createDirectories(outDir)
        for (moduleId in listOf("M01", "M02", "M03", "M04")) {
            val payload = ftphPayload(moduleId)
            val signed = OperationalProfileTestSigning.signPayloadV2(payload)
            Files.write(outDir.resolve("$moduleId.bin"), signed)
        }
    }

    @Test
    fun deskSignedFtphProfilesSelfVerifyOnJvm() {
        val publicKey =
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(OperationalProfileTestSigning.publicKeySpki))
        for (moduleId in listOf("M01", "M02", "M03", "M04")) {
            val signed = OperationalProfileTestSigning.signPayloadV2(ftphPayload(moduleId))
            val envelope = AuthenticatedProfileRevisionEnvelope.parse(signed)!!
            val message = AuthenticatedProfileRevisionEnvelope.signatureInput(envelope.protectedBytes)
            assertTrue(
                moduleId,
                EcdsaP256Verifier.verify(message, envelope.signatureRs, publicKey),
            )
        }
    }

    private fun ftphPayload(localModuleId: String): GenerationFactProfileTrustPayload =
        GenerationFactProfileTrustPayload(
            taskProfileRevision = 10,
            localModuleId = localModuleId,
            deploymentTrustDomainId = "talkback-operational",
            moduleBindings =
                listOf("M01", "M02", "M03", "M04").map { moduleId ->
                    ModuleSigningBinding(
                        moduleId = moduleId,
                        signerKeyVersion = 1,
                        keyState = GenerationFactKeyState.ACTIVE,
                        publicKeySpki = GenerationFactTestSigning.publicKeySpki,
                        activatedAtRevision = 10,
                    )
                },
        )
}
