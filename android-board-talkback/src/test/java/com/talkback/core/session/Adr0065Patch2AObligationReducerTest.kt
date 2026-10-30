package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0065 Patch-2A — obligation lifecycle reducer fixtures. */
class Adr0065Patch2AObligationReducerTest {

    private val config =
        PairwiseMeshObligationReducer.Config(
            checkingGraceMs = 10_000L,
        )

    private fun reduce(
        current: PairwiseMeshObligationState,
        observation: PairwiseMeshObligationObservation,
    ): PairwiseMeshObligationReducer.Result =
        PairwiseMeshObligationReducer.reduce(
            PairwiseMeshObligationReducer.Input(
                current = current,
                observation = observation,
                config = config,
            )
        )

    /** P2-E1 — invite → accept → ICE connected → SATISFIED. */
    @Test
    fun p2E1_nominalProgression_reachesSatisfied() {
        var state = PairwiseMeshObligationState.PENDING
        state = reduce(state, PairwiseMeshObligationObservation.DeliveryInviteDispatched).next
        assertEquals(PairwiseMeshObligationState.SIGNALING_IN_FLIGHT, state)

        state = reduce(state, PairwiseMeshObligationObservation.DeliveryGroupAcceptReceived).next
        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, state)

        state =
            reduce(
                state,
                PairwiseMeshObligationObservation.MediaIceConnected(
                    signalingCompleted = true,
                    bindingValid = true,
                    bindingFenced = false,
                ),
            ).next
        assertEquals(PairwiseMeshObligationState.SATISFIED, state)
    }

    /** P2-E2 — GROUP_ACCEPT + CHECKING → MEDIA_PENDING, not SATISFIED. */
    @Test
    fun p2E2_groupAcceptWithChecking_staysMediaPending() {
        var state = PairwiseMeshObligationState.SIGNALING_IN_FLIGHT
        state = reduce(state, PairwiseMeshObligationObservation.DeliveryGroupAcceptReceived).next
        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, state)

        state = reduce(state, PairwiseMeshObligationObservation.MediaIceChecking(elapsedMs = 5_000L)).next
        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, state)

        val incompleteIce =
            reduce(
                state,
                PairwiseMeshObligationObservation.MediaIceConnected(
                    signalingCompleted = false,
                    bindingValid = true,
                    bindingFenced = false,
                ),
            )
        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, incompleteIce.next)
        assertFalse(incompleteIce.reason.contains("SATISFIED"))
    }

    /** P2-E7 — meshMissingIce diagnostic must not downgrade. */
    @Test
    fun p2E7_meshMissingIceDiagnostic_noDowngrade() {
        val current = PairwiseMeshObligationState.MEDIA_PENDING
        val outcome =
            reduce(
                current,
                PairwiseMeshObligationObservation.MeshMissingIceDiagnostic(meshMissingIce = true),
            )
        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, outcome.next)
        assertTrue(outcome.reason.contains("DIAGNOSTIC"))
    }

    /** R-1 — CHECKING within grace must not downgrade SATISFIED. */
    @Test
    fun r1_checkingWithinGrace_doesNotDowngradeSatisfied() {
        val outcome =
            reduce(
                PairwiseMeshObligationState.SATISFIED,
                PairwiseMeshObligationObservation.MediaIceChecking(elapsedMs = 5_000L),
            )
        assertEquals(PairwiseMeshObligationState.SATISFIED, outcome.next)
    }

    /** R-2 — FAILED observation downgrades to ACTIVATION_REQUIRED. */
    @Test
    fun r2_failedIce_downgradesToActivationRequired() {
        val outcome =
            reduce(
                PairwiseMeshObligationState.SATISFIED,
                PairwiseMeshObligationObservation.MediaIceLost(iceState = "FAILED"),
            )
        assertEquals(PairwiseMeshObligationState.ACTIVATION_REQUIRED, outcome.next)
    }

    @Test
    fun deliveryGroupAccept_fromPending_neverSatisfiedDirectly() {
        val outcome =
            reduce(
                PairwiseMeshObligationState.PENDING,
                PairwiseMeshObligationObservation.DeliveryGroupAcceptReceived,
            )
        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, outcome.next)
    }

    @Test
    fun apply_updatesObligationCopyThroughReducer() {
        val edge = PairwiseMeshEdgeKey("M02", "M04")
        val obligation =
            PairwiseMeshAdmissionObligation(
                edge = edge,
                offerer = edge.firstModuleId(),
                answerer = edge.secondModuleId(),
                signalingSatisfied = false,
                lifecycle = PairwiseMeshObligationState.SIGNALING_IN_FLIGHT,
            )
        val updated =
            PairwiseMeshObligationReducer.apply(
                obligation,
                PairwiseMeshObligationObservation.DeliveryGroupAcceptReceived,
            )
        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, updated.lifecycle)
        assertFalse(updated.signalingSatisfied)
    }

    @Test
    fun bindingIncarnationInvalid_downgradesFromMediaPending() {
        val outcome =
            reduce(
                PairwiseMeshObligationState.MEDIA_PENDING,
                PairwiseMeshObligationObservation.BindingIncarnationInvalid(pcGeneration = 4L),
            )
        assertEquals(PairwiseMeshObligationState.ACTIVATION_REQUIRED, outcome.next)
    }
}

private fun PairwiseMeshEdgeKey.firstModuleId() = com.talkback.core.model.ModuleId(first)

private fun PairwiseMeshEdgeKey.secondModuleId() = com.talkback.core.model.ModuleId(second)
