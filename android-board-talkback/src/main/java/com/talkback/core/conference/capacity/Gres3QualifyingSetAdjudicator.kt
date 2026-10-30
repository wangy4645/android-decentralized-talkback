package com.talkback.core.conference.capacity

enum class Gres3CloseoutVerdict {
    PASS,
    NOT_PROVEN,
    INVALID_SET,
}

data class Gres3QualifyingRunRef(
    val runId: String,
    val manifest: Gres3AdjudicatorManifest,
    val config: Gres3AdjudicatorConfig,
    val summary: Gres3AdjudicatorSummary,
    val bundleComplete: Boolean = true,
)

data class Gres3CloseoutOutput(
    val verdict: Gres3CloseoutVerdict,
    val provenNMax: Int?,
    val runOutputs: List<Gres3AdjudicatorOutput>,
    val reasons: List<Gres3AdjudicationReason>,
)

/**
 * C4 E1 closeout: 3 independent VALID PASS @ N=9 → ProvenNMax >= 9.
 */
object Gres3QualifyingSetAdjudicator {
    const val REQUIRED_RUN_COUNT: Int = 3

    fun adjudicateCloseout(runs: List<Gres3QualifyingRunRef>): Gres3CloseoutOutput {
        val runOutputs =
            runs.map { ref ->
                Gres3CapacityRunAdjudicator.adjudicate(
                    Gres3AdjudicatorInput(
                        manifest = ref.manifest,
                        config = ref.config,
                        summary = ref.summary,
                        bundleComplete = ref.bundleComplete,
                    ),
                )
            }

        val reasons = mutableListOf<Gres3AdjudicationReason>()
        if (runs.size != REQUIRED_RUN_COUNT) {
            reasons += Gres3AdjudicationReason.MEASUREMENT_INCOMPLETE
            return Gres3CloseoutOutput(
                verdict = Gres3CloseoutVerdict.INVALID_SET,
                provenNMax = null,
                runOutputs = runOutputs,
                reasons = reasons,
            )
        }

        val lineageViolations = validateLineage(runs.map { it.manifest })
        if (lineageViolations.isNotEmpty()) {
            reasons += lineageViolations.map { mapLineageViolation(it) }
            return Gres3CloseoutOutput(
                verdict = Gres3CloseoutVerdict.INVALID_SET,
                provenNMax = null,
                runOutputs = runOutputs,
                reasons = reasons.distinct(),
            )
        }

        val allPass =
            runOutputs.all {
                it.validity == Gres3Validity.VALID &&
                    it.runVerdict == Gres3RunVerdict.PASS &&
                    it.eligibleForQualifyingSet
            }

        return if (allPass) {
            Gres3CloseoutOutput(
                verdict = Gres3CloseoutVerdict.PASS,
                provenNMax = Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT,
                runOutputs = runOutputs,
                reasons = emptyList(),
            )
        } else {
            val capacityFail = runOutputs.any { it.runVerdict == Gres3RunVerdict.FAIL }
            Gres3CloseoutOutput(
                verdict = if (capacityFail) Gres3CloseoutVerdict.NOT_PROVEN else Gres3CloseoutVerdict.INVALID_SET,
                provenNMax = null,
                runOutputs = runOutputs,
                reasons = runOutputs.flatMap { it.reasons }.distinct(),
            )
        }
    }

    fun validateLineage(manifests: List<Gres3AdjudicatorManifest>): List<Gres3QualifyingSetGuard.Violation> {
        val fingerprints =
            manifests.map {
                Gres3QualifyingSetGuard.ManifestFingerprint(
                    runClass = it.runClass,
                    appBuildSha = it.appBuildSha,
                    benchmarkConfigHash = it.benchmarkConfigHash,
                    targetCount = it.topologyLegCount,
                )
            }
        return Gres3QualifyingSetGuard.validateHomogeneousSet(fingerprints)
    }

    private fun mapLineageViolation(violation: Gres3QualifyingSetGuard.Violation): Gres3AdjudicationReason =
        when {
            violation.field.startsWith("runClass") -> Gres3AdjudicationReason.QUALIFICATION_NOT_ELIGIBLE
            violation.field.startsWith("targetCount") -> Gres3AdjudicationReason.TARGET_COUNT_NOT_9
            violation.field.startsWith("appBuildSha") ||
                violation.field.startsWith("benchmarkConfigHash") -> Gres3AdjudicationReason.CONFIG_MISMATCH
            else -> Gres3AdjudicationReason.CONFIG_MISMATCH
        }
}
