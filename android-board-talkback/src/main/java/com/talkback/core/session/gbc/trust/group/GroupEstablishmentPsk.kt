package com.talkback.core.session.gbc.trust.group

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 0 GKE: derive group MAC key from existing app sharedSecret (UI unchanged).
 * Separate domain string from [com.talkback.core.security.SignalSecurity] canonical signing.
 */
object GroupEstablishmentPsk {
    private const val INFO_V1 = "talkback-gke-establishment-v1"

    fun macKey(
        sharedSecret: String,
        deploymentTrustDomainId: String,
    ): ByteArray {
        require(sharedSecret.isNotBlank()) { "sharedSecret required" }
        require(deploymentTrustDomainId.isNotBlank()) { "deploymentTrustDomainId required" }
        val material = "$INFO_V1|${deploymentTrustDomainId.trim()}|${sharedSecret.trim()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(material.toByteArray(StandardCharsets.UTF_8))
    }
}
