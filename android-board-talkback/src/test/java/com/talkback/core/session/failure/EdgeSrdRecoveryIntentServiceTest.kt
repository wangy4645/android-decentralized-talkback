package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceNativeExecutionDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSrdRecoveryIntentServiceTest {

    private val sessionId = "sess-1"
    private val edgeKey = "sess-1|M03"
    private val scope = ConferenceFailureGenerationScope(
        conferenceSessionId = sessionId,
        meshGeneration = 1L,
        pcGeneration = 4L,
    )
    private val terminal = ConferenceFailureTerminal.EdgeFailed(
        edgeKey = edgeKey,
        runtimeDomainRef = "shared-factory",
        generationScope = scope,
    )

    @Test
    fun scenarioE_createsRecoveryRequested_whenDomainIdle() {
        val lines = mutableListOf<String>()
        val service = EdgeSrdRecoveryIntentService(
            domainSnapshotProvider = { null },
            logLine = { lines += it },
        )
        val intent = service.onScenarioEdgeFailed(trigger(moduleId = "M03"))
        assertNotNull(intent)
        assertEquals(EdgeSrdRecoveryState.RECOVERY_REQUESTED, intent!!.state)
        assertTrue(lines.any { it.startsWith("RECOVERY_REQUESTED") })
        assertTrue(lines.none { it.startsWith("RECOVERY_WAITING_FOR_DOMAIN") })
    }

    @Test
    fun scenarioE_movesToWaiting_whenFailureEdgeHoldsDomainLease() {
        val lines = mutableListOf<String>()
        val domain = ConferenceNativeExecutionDomain()
        domain.requestLease(edgeKey)
        val service = EdgeSrdRecoveryIntentService(
            domainSnapshotProvider = { domain.currentSnapshot() },
            logLine = { lines += it },
        )
        val intent = service.onScenarioEdgeFailed(trigger(moduleId = "M03"))
        assertEquals(EdgeSrdRecoveryState.WAITING_FOR_SAFE_ADMISSION, intent?.state)
        assertTrue(lines.any { it.startsWith("RECOVERY_REQUESTED") })
        assertTrue(lines.any { it.startsWith("RECOVERY_WAITING_FOR_DOMAIN") })
    }

    @Test
    fun duplicateScenarioE_sameScope_suppressesSecondIntent() {
        val lines = mutableListOf<String>()
        val service = EdgeSrdRecoveryIntentService(logLine = { lines += it })
        val first = service.onScenarioEdgeFailed(trigger(moduleId = "M03"))
        val second = service.onScenarioEdgeFailed(trigger(moduleId = "M03"))
        assertNotNull(first)
        assertEquals(first, second)
        assertTrue(lines.any { it.contains("RECOVERY_SUPPRESSED") && it.contains("DUPLICATE_ACTIVE") })
        assertEquals(1, service.intentsForSession(sessionId).size)
    }

    @Test
    fun clearSession_terminalsActiveIntent() {
        val lines = mutableListOf<String>()
        val service = EdgeSrdRecoveryIntentService(logLine = { lines += it })
        service.onScenarioEdgeFailed(trigger(moduleId = "M03"))
        service.clearSession(sessionId)
        assertTrue(service.intentsForSession(sessionId).isEmpty())
        assertTrue(lines.any { it.startsWith("RECOVERY_TERMINAL") && it.contains("SESSION_END") })
    }

    @Test
    fun shouldWaitForSafeAdmission_falseWhenHolderIsDifferentEdge() {
        val domain = ConferenceNativeExecutionDomain()
        domain.requestLease("sess-1|M02")
        val service = EdgeSrdRecoveryIntentService(
            domainSnapshotProvider = { domain.currentSnapshot() },
        )
        assertTrue(!service.shouldWaitForSafeAdmission(edgeKey))
    }

    @Test
    fun shouldWaitForSafeAdmission_trueWhenHolderStuckOnFailureEdge() {
        val domain = ConferenceNativeExecutionDomain()
        domain.requestLease(edgeKey)
        domain.markStuck(edgeKey)
        val service = EdgeSrdRecoveryIntentService(
            domainSnapshotProvider = { domain.currentSnapshot() },
        )
        assertTrue(service.shouldWaitForSafeAdmission(edgeKey))
    }

    private fun trigger(
        moduleId: String,
        pcGeneration: Long? = 4L,
    ): EdgeSrdRecoveryTrigger =
        EdgeSrdRecoveryTrigger(
            terminal = terminal,
            remoteModuleId = moduleId,
            conferenceGeneration = 1L,
            offerLineageId = "CR4",
            realizationAttemptId = "RA4",
            causeFact = "SRD_TIMEOUT",
        )
}
