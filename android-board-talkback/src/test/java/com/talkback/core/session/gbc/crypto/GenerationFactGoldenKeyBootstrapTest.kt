package com.talkback.core.session.gbc.crypto

import org.junit.Ignore
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class GenerationFactGoldenKeyBootstrapTest {
    @Ignore("Manual PV-E key export helper")
    @Test
    fun printFixedGoldenKeyMaterial() {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        val privateHex = keyPair.private.encoded.toHex()
        val publicHex = keyPair.public.encoded.toHex()
        println("PRIVATE_PKCS8_HEX=$privateHex")
        println("PUBLIC_SPKI_HEX=$publicHex")
        println("PRIVATE_B64=${Base64.getEncoder().encodeToString(keyPair.private.encoded)}")
        println("PUBLIC_B64=${Base64.getEncoder().encodeToString(keyPair.public.encoded)}")
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
