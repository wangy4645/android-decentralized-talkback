package com.talkback.core.conference.session

/**
 * SOURCE generation succession — authoritative replace-before-ready sequencing.
 *
 * For the same [moduleId], [MEMBER_REPLACED] must complete before [MEMBER_MEDIA_READY]
 * may install a higher generation. Prevents plain [installMember] from bypassing revoke.
 */
internal enum class SourceSuccessionMediaReadyDecision {
    INSTALL,
    ALREADY_INSTALLED,
    DEFER_REPLACE_PENDING,
    REJECT_STALE_GENERATION,
}

internal object ConferenceSessionMediaSourceSuccessionLifecycle {
    fun resolveMediaReadyDecision(
        bindingIncarnation: Long,
        catalogIncarnation: Long?,
        replacePending: Boolean,
        bindingMediaKeyEpoch: Long,
        sessionMediaKeyEpoch: Long,
    ): SourceSuccessionMediaReadyDecision {
        if (replacePending) {
            return SourceSuccessionMediaReadyDecision.DEFER_REPLACE_PENDING
        }
        if (bindingMediaKeyEpoch != sessionMediaKeyEpoch) {
            return SourceSuccessionMediaReadyDecision.INSTALL
        }
        val catalog = catalogIncarnation
        if (catalog == null) {
            return SourceSuccessionMediaReadyDecision.INSTALL
        }
        return when {
            bindingIncarnation == catalog -> SourceSuccessionMediaReadyDecision.ALREADY_INSTALLED
            bindingIncarnation < catalog -> SourceSuccessionMediaReadyDecision.REJECT_STALE_GENERATION
            else -> SourceSuccessionMediaReadyDecision.DEFER_REPLACE_PENDING
        }
    }
}
