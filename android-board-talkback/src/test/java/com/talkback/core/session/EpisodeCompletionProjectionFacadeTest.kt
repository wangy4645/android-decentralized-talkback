package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR5-3 M0 — V0-1 / V0-2 shadow facade characterization.
 * Does not touch UVCP / MeetingPresenceDisplay.
 */
class EpisodeCompletionProjectionFacadeTest {

    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        logs.clear()
        EpisodeCompletionProjectionFacade.resetForTest(enabled = false)
        CompletionObservationProjection.resetForTest { logs.add(it) }
    }

    @After
    fun tearDown() {
        EpisodeCompletionProjectionFacade.resetForTest(enabled = false)
        CompletionObservationProjection.resetForTest()
    }

    private fun observation(
        candidate: CompletionObservationProjection.CompletionCandidate,
        waiting: CompletionObservationProjection.WaitingReason =
            if (candidate == CompletionObservationProjection.CompletionCandidate.RECOVERED) {
                CompletionObservationProjection.WaitingReason.NONE
            } else {
                CompletionObservationProjection.WaitingReason.DELIVERY_PENDING
            }
    ): CompletionObservationProjection.CompletionObservationResult =
        CompletionObservationProjection.CompletionObservationResult(
            sessionId = "sess-m0",
            remoteModuleId = "M02",
            attemptId = 7L,
            obligationGeneration = 2L,
            deliveryConfirmed = candidate == CompletionObservationProjection.CompletionCandidate.RECOVERED,
            deliveryRequired = true,
            iceConnected = true,
            mediaRecoveryEvidenceSatisfied = true,
            mediaUnavailableAdvisory = false,
            controlReconciled = true,
            topologySatisfied = true,
            hasUncoveredDeferredIntent = false,
            deliveryConfirmedOutcome = null,
            candidate = candidate,
            waitingReason = waiting,
            attemptState = CompletionObservationProjection.AttemptObservationState.ATTEMPT_SUCCEEDED,
            attemptTerminal = true,
            obligationOpen = candidate != CompletionObservationProjection.CompletionCandidate.RECOVERED,
            episodeCompletionCandidate = candidate
        )

    @Test
    fun v0_1_recoveredMapsToRecoveredTaxonomy() {
        val p = EpisodeCompletionProjectionFacade.fromObservation(
            observation(CompletionObservationProjection.CompletionCandidate.RECOVERED),
            completionEpochMs = 1000L
        )
        assertEquals(EpisodeCompletionState.RECOVERED, p.completionState)
        assertEquals(CompletionObservationProjection.WaitingReason.NONE.name, p.completionReason)
        assertEquals(EpisodeCompletionProjectionFacade.COMPLETION_SOURCE_OBSERVATION, p.completionSource)
        assertEquals(2L, p.obligationGeneration)
        assertEquals(1000L, p.completionEpochMs)
    }

    @Test
    fun v0_1_waitingAndContinueMapToOpenNonTerminal() {
        val waiting = EpisodeCompletionProjectionFacade.fromObservation(
            observation(CompletionObservationProjection.CompletionCandidate.WAITING)
        )
        val cont = EpisodeCompletionProjectionFacade.fromObservation(
            observation(CompletionObservationProjection.CompletionCandidate.CONTINUE_RECOVERY)
        )
        assertEquals(EpisodeCompletionState.OPEN, waiting.completionState)
        assertEquals(EpisodeCompletionState.OPEN, cont.completionState)
        assertTrue(
            setOf(
                EpisodeCompletionState.RECOVERED,
                EpisodeCompletionState.FAILED_FINAL,
                EpisodeCompletionState.OPEN
            ).containsAll(listOf(waiting.completionState, cont.completionState))
        )
    }

    @Test
    fun v0_2_projectionSurfaceHasNoLifecycleMachineryFields() {
        val p = EpisodeCompletionProjectionFacade.fromObservation(
            observation(CompletionObservationProjection.CompletionCandidate.WAITING)
        )
        val line = p.toShadowLogLine()
        // Identity + completion facts only
        assertTrue(line.contains("completionState="))
        assertTrue(line.contains("completionReason="))
        assertTrue(line.contains("completionSource="))
        assertTrue(line.contains("obligationGen="))
        // Leakage forbidden tokens must not appear on shadow surface
        assertFalse(line.contains("attemptId"))
        assertFalse(line.contains("attemptState"))
        assertFalse(line.contains("RecoveryPhase"))
        assertFalse(line.contains("deferredIntent"))
        assertFalse(line.contains("ActivationEvidence"))
        assertFalse(line.contains("EXECUTED"))
        assertFalse(line.contains("BIDIRECTIONAL_READY"))
        assertFalse(line.contains("MediaState"))
        assertFalse(line.contains("iceConnected"))
        assertFalse(line.contains("mediaUnavailable"))
        // data class property names (reflection-free check via toString shape)
        val props = EpisodeCompletionProjection::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(props.any { it.contains("attempt", ignoreCase = true) })
        assertFalse(props.any { it.contains("phase", ignoreCase = true) })
        assertFalse(props.any { it.contains("intent", ignoreCase = true) })
        assertFalse(props.any { it.contains("media", ignoreCase = true) })
        assertFalse(props.any { it.contains("activation", ignoreCase = true) })
    }

    @Test
    fun shadowDisabled_emitsNothing() {
        EpisodeCompletionProjectionFacade.shadowEnabled = false
        CompletionObservationProjection.logObservations(
            observation(CompletionObservationProjection.CompletionCandidate.RECOVERED)
        )
        assertFalse(logs.any { it.startsWith("EPISODE_COMPLETION_PROJECTION_SHADOW") })
        assertTrue(logs.any { it.startsWith("RECOVERY_COMPLETION_OBSERVATION") })
    }

    @Test
    fun shadowEnabled_emitsOneLinePerObservation() {
        EpisodeCompletionProjectionFacade.shadowEnabled = true
        CompletionObservationProjection.logObservations(
            observation(CompletionObservationProjection.CompletionCandidate.RECOVERED),
        )
        val shadow = logs.filter { it.startsWith("EPISODE_COMPLETION_PROJECTION_SHADOW") }
        assertEquals(1, shadow.size)
        assertTrue(shadow.single().contains("completionState=RECOVERED"))
    }

    @Test
    fun m1_onObservation_cachesLatestForAdapter() {
        EpisodeCompletionProjectionFacade.shadowEnabled = false
        val obs = observation(CompletionObservationProjection.CompletionCandidate.RECOVERED)
        EpisodeCompletionProjectionFacade.onObservation(obs, logSink = { logs.add(it) }, completionEpochMs = 42L)
        val latest = EpisodeCompletionProjectionFacade.latest("sess-m0", "M02")
        assertEquals(EpisodeCompletionState.RECOVERED, latest!!.completionState)
        assertEquals(42L, latest.completionEpochMs)
        assertFalse(logs.any { it.startsWith("EPISODE_COMPLETION_PROJECTION_SHADOW") })
    }

    @Test
    fun m1_shadowStillOptionalWhenCaching() {
        EpisodeCompletionProjectionFacade.shadowEnabled = true
        EpisodeCompletionProjectionFacade.onObservation(
            observation(CompletionObservationProjection.CompletionCandidate.WAITING),
            logSink = { logs.add(it) }
        )
        assertEquals(EpisodeCompletionState.OPEN, EpisodeCompletionProjectionFacade.latest("sess-m0", "M02")!!.completionState)
        assertEquals(1, logs.count { it.startsWith("EPISODE_COMPLETION_PROJECTION_SHADOW") })
    }
}