package com.talkback.core.conference.capacity

/**
 * H1d.5a: single-attempt egress warm-up validity (no retry).
 */
data class Gres3EgressWarmupLegFailure(
    val legIndex: Int,
    val receiverModuleId: String,
    val moduleFixedIp: String,
    val mediaPort: Int,
    val endpointKey: String,
    val exceptionClass: String,
    val message: String?,
    val causeClass: String?,
    val causeMessage: String?,
)

data class Gres3EgressWarmupResult(
    val attempted: Boolean,
    val legsSucceeded: Int,
    val legsFailed: Int,
    val legFailures: List<Gres3EgressWarmupLegFailure>,
    val setupError: String? = null,
) {
    val succeeded: Boolean
        get() =
            attempted &&
                setupError == null &&
                legsSucceeded == Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT &&
                legsFailed == 0 &&
                legFailures.isEmpty()
}

enum class Gres3HarnessLifecycleState {
    SOCKET_CONFIGURED,
    TIME_WARMUP,
    EGRESS_WARMUP,
    MEASUREMENT,
    ABORTED_BEFORE_MEASUREMENT,
}

enum class Gres3EgressWarmupVerdict {
    VALID,
    INVALID,
}

data class Gres3H1d5aAdjudicatorOutput(
    val verdict: Gres3EgressWarmupVerdict,
    val lifecycleState: Gres3HarnessLifecycleState,
    val warmEgressSucceeded: Boolean,
    val warmupAttempts: Int,
    val legsSucceeded: Int,
    val legsFailed: Int,
    val legFailures: List<Gres3EgressWarmupLegFailure>,
    val setupError: String?,
    val reasons: List<String>,
)

object Gres3H1d5aAdjudicator {
    const val WARMUP_ATTEMPTS: Int = 1

    fun adjudicate(warmupResult: Gres3EgressWarmupResult): Gres3H1d5aAdjudicatorOutput {
        val reasons = mutableListOf<String>()
        if (!warmupResult.attempted) {
            reasons += "EGRESS_WARMUP_NOT_ATTEMPTED"
        }
        warmupResult.setupError?.let { reasons += "EGRESS_WARMUP_SETUP_ERROR:$it" }
        if (warmupResult.legsFailed > 0) {
            reasons += "EGRESS_WARMUP_LEG_FAILURES:${warmupResult.legsFailed}"
        }
        if (!warmupResult.succeeded) {
            reasons += "EGRESS_WARMUP_NOT_9_OF_9"
        }
        val lifecycleState =
            if (warmupResult.succeeded) {
                Gres3HarnessLifecycleState.MEASUREMENT
            } else {
                Gres3HarnessLifecycleState.ABORTED_BEFORE_MEASUREMENT
            }
        return Gres3H1d5aAdjudicatorOutput(
            verdict = if (warmupResult.succeeded) Gres3EgressWarmupVerdict.VALID else Gres3EgressWarmupVerdict.INVALID,
            lifecycleState = lifecycleState,
            warmEgressSucceeded = warmupResult.succeeded,
            warmupAttempts = WARMUP_ATTEMPTS,
            legsSucceeded = warmupResult.legsSucceeded,
            legsFailed = warmupResult.legsFailed,
            legFailures = warmupResult.legFailures,
            setupError = warmupResult.setupError,
            reasons = reasons,
        )
    }
}
