package com.talkback.core.session.gbc.issuance

import java.security.MessageDigest

/**
 * SPKI fingerprint helper for FTPH-IA-T1 post-provision verification.
 */
object GenerationFactSignerFingerprint {
    fun spkiSha256Hex(spki: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }
}
