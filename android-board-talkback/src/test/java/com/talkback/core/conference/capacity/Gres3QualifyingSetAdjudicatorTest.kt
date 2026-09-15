package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gres3QualifyingSetAdjudicatorTest {
    @Test
    fun threeValidPass_provenNMaxAtLeastNine() {
        val runs =
            (1..3).map { index ->
                qualifyingRunRef("run-00$index")
            }
        val output = Gres3QualifyingSetAdjudicator.adjudicateCloseout(runs)
        assertEquals(Gres3CloseoutVerdict.PASS, output.verdict)
        assertEquals(9, output.provenNMax)
        assertTrue(output.reasons.isEmpty())
    }

    @Test
    fun qualificationRun_rejectedFromSet() {
        val runs =
            listOf(
                qualifyingRunRef("run-001"),
                qualifyingRunRef("run-002"),
                Gres3QualifyingRunRef(
                    runId = "run-003",
                    manifest =
                        Gres3CapacityRunAdjudicatorFixtureTest.passManifest().copy(
                            runClass = Gres3RunClass.QUALIFICATION,
                        ),
                    config = Gres3CapacityRunAdjudicatorFixtureTest.passConfig(),
                    summary = Gres3CapacityRunAdjudicatorFixtureTest.passSummary(),
                ),
            )
        val output = Gres3QualifyingSetAdjudicator.adjudicateCloseout(runs)
        assertEquals(Gres3CloseoutVerdict.INVALID_SET, output.verdict)
        assertNull(output.provenNMax)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.QUALIFICATION_NOT_ELIGIBLE))
    }

    @Test
    fun configMismatchAcrossRuns_invalidSet() {
        val runs =
            listOf(
                qualifyingRunRef("run-001"),
                qualifyingRunRef("run-002"),
                Gres3QualifyingRunRef(
                    runId = "run-003",
                    manifest =
                        Gres3CapacityRunAdjudicatorFixtureTest.passManifest().copy(
                            appBuildSha = "other-build",
                        ),
                    config = Gres3CapacityRunAdjudicatorFixtureTest.passConfig(),
                    summary = Gres3CapacityRunAdjudicatorFixtureTest.passSummary(),
                ),
            )
        val output = Gres3QualifyingSetAdjudicator.adjudicateCloseout(runs)
        assertEquals(Gres3CloseoutVerdict.INVALID_SET, output.verdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.CONFIG_MISMATCH))
    }

    @Test
    fun oneCapacityFail_notProven() {
        val runs =
            listOf(
                qualifyingRunRef("run-001"),
                qualifyingRunRef("run-002"),
                Gres3QualifyingRunRef(
                    runId = "run-003",
                    manifest = Gres3CapacityRunAdjudicatorFixtureTest.passManifest(),
                    config = Gres3CapacityRunAdjudicatorFixtureTest.passConfig(),
                    summary =
                        Gres3CapacityRunAdjudicatorFixtureTest.passSummary().copy(
                            slotDeadlineMissCount = 1,
                        ),
                ),
            )
        val output = Gres3QualifyingSetAdjudicator.adjudicateCloseout(runs)
        assertEquals(Gres3CloseoutVerdict.NOT_PROVEN, output.verdict)
        assertNull(output.provenNMax)
    }

    @Test
    fun fewerThanThreeRuns_invalidSet() {
        val output =
            Gres3QualifyingSetAdjudicator.adjudicateCloseout(
                listOf(qualifyingRunRef("run-001")),
            )
        assertEquals(Gres3CloseoutVerdict.INVALID_SET, output.verdict)
    }

    private fun qualifyingRunRef(runId: String): Gres3QualifyingRunRef =
        Gres3QualifyingRunRef(
            runId = runId,
            manifest = Gres3CapacityRunAdjudicatorFixtureTest.passManifest(),
            config = Gres3CapacityRunAdjudicatorFixtureTest.passConfig(),
            summary = Gres3CapacityRunAdjudicatorFixtureTest.passSummary(),
        )
}
