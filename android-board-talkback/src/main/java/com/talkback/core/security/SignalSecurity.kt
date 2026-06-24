package com.talkback.core.security

import com.talkback.core.model.SignalEnvelope
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SignalSecurity {
    fun sign(unsigned: SignalEnvelope, sharedSecret: String): String {
        if (sharedSecret.isBlank()) return ""
        val canonical = canonicalString(unsigned, includeSignature = false)
        return hmacSha256Hex(sharedSecret, canonical)
    }

    fun verify(signed: SignalEnvelope, sharedSecret: String): Boolean {
        if (sharedSecret.isBlank() || signed.signature.isBlank()) return false
        val expected = sign(signed, sharedSecret)
        if (expected.isEmpty()) return false
        return constantTimeEquals(expected, signed.signature)
    }

    private fun canonicalString(envelope: SignalEnvelope, includeSignature: Boolean): String {
        val base = listOf(
            envelope.type.name,
            envelope.from.moduleId.value,
            envelope.from.endpointId.value,
            envelope.to?.moduleId?.value ?: "",
            envelope.to?.endpointId?.value ?: "",
            envelope.sessionId,
            envelope.timestampMs.toString(),
            envelope.payload,
            envelope.nonce
        ).joinToString("|")
        return if (!includeSignature) base else "$base|${envelope.signature}"
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }
}
