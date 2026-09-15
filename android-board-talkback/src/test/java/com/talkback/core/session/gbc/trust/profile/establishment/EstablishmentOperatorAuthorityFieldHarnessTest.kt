package com.talkback.core.session.gbc.trust.profile.establishment

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstation Gradle entrypoint for operator authority batch signing.
 *
 *   EP_BATCH_DIR=<path> ./gradlew :android-board-talkback:testDebugUnitTest \
 *     --tests EstablishmentOperatorAuthorityFieldHarnessTest.runBatchFromEnv
 */
class EstablishmentOperatorAuthorityFieldHarnessTest {
    @Test
    fun runBatchFromEnv() {
        if (System.getenv("EP_RUN_AUTHORITY_BATCH_TEST") != "1") {
            return // field batch only — set EP_RUN_AUTHORITY_BATCH_TEST=1 with EP_BATCH_DIR
        }
        val batchDir = System.getenv("EP_BATCH_DIR")?.let(::File)
            ?: error("EP_BATCH_DIR required when EP_RUN_AUTHORITY_BATCH_TEST=1")
        val result = EstablishmentOperatorAuthorityFieldHarness.run(batchDir)
        assertTrue(result.message, result.success)
        assertTrue(result.outputs.containsKey("M01"))
        assertTrue(result.outputs.containsKey("M02"))
        assertTrue(File(batchDir, "PROVISIONING_MANIFEST.txt").exists())
        val thirdPeer =
            listOf("M03", "M04").firstOrNull { File(batchDir, "$it-evidence.txt").exists() }
        if (thirdPeer != null) {
            assertTrue(result.outputs.containsKey(thirdPeer))
        }
    }

    @Test
    fun trioProvisioning_writesSignedDeliveriesForAllPeers_m04Label() {
        trioProvisioning_writesSignedDeliveriesForAllPeers("M04")
    }

    @Test
    fun trioProvisioning_writesSignedDeliveriesForAllPeers_m03DeviceModuleId() {
        trioProvisioning_writesSignedDeliveriesForAllPeers("M03")
    }

    private fun trioProvisioning_writesSignedDeliveriesForAllPeers(thirdPeerModuleId: String) {
        val batchDir = File.createTempFile("ep-trio-batch", null).apply { delete(); mkdirs() }
        writeEvidence(batchDir, "M01", 1L)
        writeEvidence(batchDir, "M02", 1L)
        writeEvidence(batchDir, thirdPeerModuleId, 1L)

        val result = EstablishmentOperatorAuthorityFieldHarness.run(batchDir)
        assertTrue(result.message, result.success)
        assertEquals(setOf("M01", "M02", thirdPeerModuleId), result.outputs.keys)
        val manifest = File(batchDir, "PROVISIONING_MANIFEST.txt").readText()
        assertTrue(manifest.contains("provisioning_topology=trio"))
        assertTrue(manifest.contains("$thirdPeerModuleId.establishmentKeyVersion="))
        listOf("M01", "M02", thirdPeerModuleId).forEach { moduleId ->
            assertTrue(File(batchDir, "establishment-signed-$moduleId.bin").exists())
        }
    }

    private fun writeEvidence(
        batchDir: File,
        moduleId: String,
        version: Long,
    ) {
        val spki = EstablishmentProfileRevisionFixtureSupport.generateRsa3072Spki()
        val fingerprint = spki.sha256Hex()
        val body =
            """
            moduleId=$moduleId
            algorithm=RSA_3072_OAEP_SHA256_MGF1_SHA1
            publicKeyFingerprintSha256Hex=$fingerprint
            keyAlias=talkback-establishment-$moduleId-v$version
            localKeyGeneration=1
            publicKeySpkiBase64=${Base64.getEncoder().encodeToString(spki)}
            operatorAssignedEstablishmentKeyVersion=$version
            """.trimIndent()
        File(batchDir, "$moduleId-evidence.txt").writeText(body)
    }
}
