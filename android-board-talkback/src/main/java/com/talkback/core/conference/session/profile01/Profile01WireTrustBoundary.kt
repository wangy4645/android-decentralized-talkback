package com.talkback.core.conference.session.profile01

/**
 * Profile 01 cryptographic trust boundary (signature / digest verification).
 *
 * Wire parser delegates here — does not decide media lifecycle.
 */
fun interface Profile01WireTrustBoundary {
    fun verifySignedFact(signedFactBytes: ByteArray): Profile01WireVerificationResult
}

sealed class Profile01WireVerificationResult {
    /**
     * @param authenticatedSignerModuleId module whose key verified the signature; set on
     *   production/golden verify paths for SOURCE semantic binding (PR-PA-SR-B0).
     */
    data class Verified(
        val factDigest: ByteArray,
        val authenticatedSignerModuleId: String? = null,
    ) : Profile01WireVerificationResult()

    data class Rejected(val reason: String) : Profile01WireVerificationResult()
}

object Profile01SourceValidationReasons {
    const val SIGNER_SENDER_MISMATCH = "SIGNER_SENDER_MISMATCH"
    const val MISSING_AUTHENTICATED_SIGNER = "MISSING_AUTHENTICATED_SIGNER"
}

/**
 * Trust boundary for golden-vector and harness inputs: exact signedFact match.
 */
class Profile01RegisteredSignedFactTrustBoundary(
    private val trusted: List<Pair<ByteArray, ByteArray>>,
) : Profile01WireTrustBoundary {
    override fun verifySignedFact(signedFactBytes: ByteArray): Profile01WireVerificationResult {
        val digest =
            trusted.firstOrNull { it.first.contentEquals(signedFactBytes) }?.second
                ?: return Profile01WireVerificationResult.Rejected("UNREGISTERED_SIGNED_FACT")
        return Profile01WireVerificationResult.Verified(digest.copyOf())
    }
}
