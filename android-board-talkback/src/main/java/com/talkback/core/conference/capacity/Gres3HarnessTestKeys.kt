package com.talkback.core.conference.capacity

/**
 * PUBLIC TEST KEYS ONLY — harness artifact ring (not production key material).
 */
internal object Gres3HarnessTestKeys {
    val masterKey: ByteArray =
        hex("000102030405060708090a0b0c0d0e0f")
    val masterSalt: ByteArray =
        hex("101112131415161718191a1b")
    const val SSRC: Int = 0x11223344
    const val ROC: Int = 0
    const val BASE_SEQ: Int = 0x1001

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
