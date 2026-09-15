package com.talkback.core.conference.capacity

enum class Gres3TopologyValidityVerdict {
    PASS,
    FAIL,
}

enum class Gres3HarnessRuntimeValidityVerdict {
    PASS,
    FAIL,
}

enum class Gres3FormalEvidenceTopologyVerdict {
    ELIGIBLE,
    NOT_ELIGIBLE,
}

enum class Gres3C3ProjectionVerdict {
    LIKELY_PASS,
    LIKELY_FAIL,
}

enum class Gres3H1cReason {
    TOPOLOGY_PREFLIGHT_FAIL,
    LOOPBACK_TOPOLOGY,
    HARNESS_INCOMPLETE,
    SEND_FAIL,
    THERMAL_PREFLIGHT_FAIL,
    QUALIFICATION_RUN_CLASS_OK,
    FANOUT_WITHIN_C3_PROJECTION,
    FANOUT_EXCEEDS_C3_PROJECTION,
}

data class Gres3H1cAdjudicatorInput(
    val topologyPreflight: Gres3TopologyPreflightResult,
    val topologyClass: Gres3TopologyClass,
    val runClass: Gres3RunClass,
    val harnessComplete: Boolean,
    val rawFlushed: Boolean,
    val sendFailCount: Int,
    val thermalPreflightPass: Boolean,
    val fanoutP99Ns: Long,
    val fanoutMaxNs: Long,
)

data class Gres3H1cAdjudicatorOutput(
    val topologyValidity: Gres3TopologyValidityVerdict,
    val harnessRuntimeValidity: Gres3HarnessRuntimeValidityVerdict,
    val formalEvidenceTopology: Gres3FormalEvidenceTopologyVerdict,
    val c3Projection: Gres3C3ProjectionVerdict,
    val reasons: List<Gres3H1cReason>,
)

/**
 * H1c adjudicator — topology + harness validity only; NOT G-RES-3 capacity PASS/FAIL.
 */
object Gres3H1cAdjudicator {
    fun adjudicate(input: Gres3H1cAdjudicatorInput): Gres3H1cAdjudicatorOutput {
        val reasons = mutableListOf<Gres3H1cReason>()

        val topologyValidity =
            if (input.topologyPreflight.passes) {
                Gres3TopologyValidityVerdict.PASS
            } else {
                reasons += Gres3H1cReason.TOPOLOGY_PREFLIGHT_FAIL
                Gres3TopologyValidityVerdict.FAIL
            }

        if (input.topologyClass == Gres3TopologyClass.LOOPBACK) {
            reasons += Gres3H1cReason.LOOPBACK_TOPOLOGY
        }

        val harnessRuntimeOk =
            input.harnessComplete &&
                input.rawFlushed &&
                input.sendFailCount == 0 &&
                input.thermalPreflightPass
        val harnessRuntimeValidity =
            if (harnessRuntimeOk) {
                Gres3HarnessRuntimeValidityVerdict.PASS
            } else {
                if (!input.harnessComplete || !input.rawFlushed) {
                    reasons += Gres3H1cReason.HARNESS_INCOMPLETE
                }
                if (input.sendFailCount > 0) {
                    reasons += Gres3H1cReason.SEND_FAIL
                }
                if (!input.thermalPreflightPass) {
                    reasons += Gres3H1cReason.THERMAL_PREFLIGHT_FAIL
                }
                Gres3HarnessRuntimeValidityVerdict.FAIL
            }

        if (input.runClass == Gres3RunClass.QUALIFICATION) {
            reasons += Gres3H1cReason.QUALIFICATION_RUN_CLASS_OK
        }

        val fanoutP99Us = input.fanoutP99Ns / 1_000L
        val fanoutMaxUs = input.fanoutMaxNs / 1_000L
        val c3Projection =
            if (fanoutP99Us <= Gres3CapacityRunAdjudicator.FANOUT_P99_MAX_US &&
                fanoutMaxUs <= Gres3CapacityRunAdjudicator.FANOUT_MAX_MAX_US
            ) {
                reasons += Gres3H1cReason.FANOUT_WITHIN_C3_PROJECTION
                Gres3C3ProjectionVerdict.LIKELY_PASS
            } else {
                reasons += Gres3H1cReason.FANOUT_EXCEEDS_C3_PROJECTION
                Gres3C3ProjectionVerdict.LIKELY_FAIL
            }

        val formalEligible =
            topologyValidity == Gres3TopologyValidityVerdict.PASS &&
                harnessRuntimeValidity == Gres3HarnessRuntimeValidityVerdict.PASS &&
                input.topologyClass != Gres3TopologyClass.LOOPBACK &&
                input.runClass == Gres3RunClass.QUALIFICATION

        return Gres3H1cAdjudicatorOutput(
            topologyValidity = topologyValidity,
            harnessRuntimeValidity = harnessRuntimeValidity,
            formalEvidenceTopology =
                if (formalEligible) {
                    Gres3FormalEvidenceTopologyVerdict.ELIGIBLE
                } else {
                    Gres3FormalEvidenceTopologyVerdict.NOT_ELIGIBLE
                },
            c3Projection = c3Projection,
            reasons = reasons.distinct(),
        )
    }
}
