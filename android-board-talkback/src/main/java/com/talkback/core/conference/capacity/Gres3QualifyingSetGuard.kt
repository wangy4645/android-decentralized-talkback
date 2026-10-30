package com.talkback.core.conference.capacity

/**
 * Mechanical qualifying-set guard (H1b adjudicator input).
 */
object Gres3QualifyingSetGuard {
    data class ManifestFingerprint(
        val runClass: Gres3RunClass,
        val appBuildSha: String,
        val benchmarkConfigHash: String,
        val targetCount: Int = Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT,
    )

    data class Violation(
        val field: String,
        val expected: String,
        val actual: String,
    )

    fun validateHomogeneousSet(manifests: List<ManifestFingerprint>): List<Violation> {
        if (manifests.isEmpty()) {
            return listOf(Violation("set", "non-empty", "empty"))
        }
        val reference = manifests.first()
        if (reference.runClass != Gres3RunClass.QUALIFYING) {
            return listOf(
                Violation(
                    field = "runClass",
                    expected = Gres3RunClass.QUALIFYING.name,
                    actual = reference.runClass.name,
                ),
            )
        }
        val violations = mutableListOf<Violation>()
        manifests.forEachIndexed { index, manifest ->
            if (manifest.runClass != Gres3RunClass.QUALIFYING) {
                violations.add(
                    Violation(
                        field = "runClass[$index]",
                        expected = Gres3RunClass.QUALIFYING.name,
                        actual = manifest.runClass.name,
                    ),
                )
            }
            if (manifest.targetCount != Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT) {
                violations.add(
                    Violation(
                        field = "targetCount[$index]",
                        expected = Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT.toString(),
                        actual = manifest.targetCount.toString(),
                    ),
                )
            }
            if (manifest.appBuildSha != reference.appBuildSha) {
                violations.add(
                    Violation(
                        field = "appBuildSha[$index]",
                        expected = reference.appBuildSha,
                        actual = manifest.appBuildSha,
                    ),
                )
            }
            if (manifest.benchmarkConfigHash != reference.benchmarkConfigHash) {
                violations.add(
                    Violation(
                        field = "benchmarkConfigHash[$index]",
                        expected = reference.benchmarkConfigHash,
                        actual = manifest.benchmarkConfigHash,
                    ),
                )
            }
        }
        return violations
    }
}
