package com.talkback.core.conference.session.profile01.wire

/**
 * P1-C field C-F7 — choose a wire-only recipientKeyVersion that is not the peer's
 * accepted establishment binding version.
 *
 * This value is a **negative wire input**, not a new establishment authority version.
 * Do not store it in trust state / Keystore / Profile.
 */
object Profile01MediaKeyPackageNegativeFixtureVersions {
    /** Sentinel band reserved for field negative fixtures. */
    const val SENTINEL_BASE: Long = 9_000_001L

    fun chooseWireMismatchVersion(actualEstablishmentKeyVersion: Long): Long {
        val candidate = SENTINEL_BASE
        return if (candidate != actualEstablishmentKeyVersion) {
            candidate
        } else {
            SENTINEL_BASE + 1L
        }
    }
}
