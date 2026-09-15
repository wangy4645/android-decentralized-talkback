package com.talkback.core.session.gbc.issuance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class Adr0057FieldTestSignerPersistenceTest {
  @Test
  fun ftph_eg5_binding_matches_persisted_runtime_signer() {
    val dir = Files.createTempDirectory("ftph-signer")
    val persistence = FileGenerationFactSignerPersistence(dir.resolve("signer.pkcs8"))
    val pkcs8 = SAMPLE_PKCS8
    val established =
      GenerationFactSignerEstablisher(persistence).establish(
        pkcs8,
        SAMPLE_SPKI,
        "adr0057-field-test-gf-signer-v1",
      )
    assertTrue(established is GenerationFactSignerEstablishResult.Established)
    val runtimeSigner = persistence.loadSigner()
    assertNotNull(runtimeSigner)
    val fp = GenerationFactSignerFingerprint.spkiSha256Hex(runtimeSigner!!.publicKeySpki)
    assertEquals(fp, GenerationFactSignerFingerprint.spkiSha256Hex(SAMPLE_SPKI))
  }

  @Test
  fun ftph_signer_create_once_rejects_different_key() {
    val dir = Files.createTempDirectory("ftph-signer-once")
    val persistence = FileGenerationFactSignerPersistence(dir.resolve("signer.pkcs8"))
    assertTrue(
      GenerationFactSignerEstablisher(persistence).establish(
        SAMPLE_PKCS8,
        SAMPLE_SPKI,
        "adr0057-field-test-gf-signer-v1",
      ) is GenerationFactSignerEstablishResult.Established,
    )
    val retry =
      GenerationFactSignerEstablisher(persistence).establish(
        byteArrayOf(1, 2, 3),
        SAMPLE_SPKI,
        "adr0057-field-test-gf-signer-v1",
      )
    assertTrue(retry is GenerationFactSignerEstablishResult.Rejected)
  }

  companion object {
    // Same golden-vector material as GenerationFactTestSigning (desk equivalence).
    private val SAMPLE_PKCS8: ByteArray =
      hex(
        "3041020100301306072a8648ce3d020106082a8648ce3d030107042730250201010420" +
          "241ed51cb84c1723036d52794fc9c7298de352deafa31bd3f438a9066ecee34a",
      )

    private val SAMPLE_SPKI: ByteArray =
      hex(
        "3059301306072a8648ce3d020106082a8648ce3d03010703420004" +
          "1deb748cf06e265b8a8d05b5c68c35e83336dca2e48208b470f1e98eaab682e" +
          "798445ccd797f583043b59ae971ae59f3cc97c1738d01df29cf37069d296a968c",
      )

    private fun hex(value: String): ByteArray =
      value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
