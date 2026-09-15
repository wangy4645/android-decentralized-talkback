package com.talkback.core.media

/**
 * ADR-0061 I2: live fence evaluated at post-close drain time (not at enqueue).
 */
fun interface CreateContinuationEligibility {
    fun isEligible(sessionId: String?, moduleId: String): Boolean
}
