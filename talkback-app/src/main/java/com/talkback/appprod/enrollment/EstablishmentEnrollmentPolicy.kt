package com.talkback.appprod.enrollment

import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition

/**
 * Lab fleet defaults for Phase 1 enrollment (see ESTABLISHMENT-ENROLLMENT-PRODUCTIZATION-001).
 * Production Phase 2 must not embed authority private keys here.
 */
object EstablishmentEnrollmentPolicy {
    const val LAB_ESTABLISHMENT_KEY_VERSION: Long = 1L

    val deploymentTrustDomainId: String =
        GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN

    fun normalizeModuleId(raw: String): String = raw.trim().uppercase()
}
