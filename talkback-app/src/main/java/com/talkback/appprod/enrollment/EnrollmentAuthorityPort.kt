package com.talkback.appprod.enrollment

interface EnrollmentAuthorityPort {
    fun decodeSignedDeliveryBase64(signedDeliveryBase64: String): Result<ByteArray>
}

/**
 * Phase 1 lab port: Base64 paste only (no QR / LAN — Phase 2).
 */
class LabEnrollmentAuthorityPort : EnrollmentAuthorityPort {
    override fun decodeSignedDeliveryBase64(signedDeliveryBase64: String): Result<ByteArray> {
        val trimmed = signedDeliveryBase64.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("signed delivery required"))
        }
        return runCatching {
            android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
        }.recoverCatching {
            android.util.Base64.decode(trimmed, android.util.Base64.NO_WRAP)
        }
    }
}
