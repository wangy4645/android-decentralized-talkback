package com.talkback.core.session.failure

/**
 * Phase A PR-A1: generation binding for failure attribution (Failure Domain Contract).
 * Not topology truth — scope for classifying terminals within a conference epoch.
 */
data class ConferenceFailureGenerationScope(
    val conferenceSessionId: String,
    val meshGeneration: Long,
    val pcGeneration: Long? = null,
)
