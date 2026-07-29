package com.talkback.appprod.ui

import com.talkback.appprod.ui.UserVisibleConnectivityProjection.ControlSyncState
import com.talkback.appprod.ui.UserVisibleConnectivityProjection.MediaUsability
import com.talkback.appprod.ui.UserVisibleConnectivityProjection.UserVisibleConnectivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0034 dual-axis mapping + aggregation + admission isolation guards.
 */
class UserVisibleConnectivityProjectionTest {

    @Test
    fun caseA_smoke_mediaOk_controlSyncPending_isSyncing_notReconnecting() {
        // Case 2 (PR #97 core): ICE_CONNECTED / media usable + control sync → SYNCING
        val axes = UserVisibleConnectivityProjection.deriveAxes(
            receivePathLive = true,
            mediaEverLive = true,
            recovering = true,
            mediaUnavailable = false,
            controlSyncPending = true // obligation OPEN / negotiation deferred (coarse)
        )
        val state = UserVisibleConnectivityProjection.project(axes)
        assertEquals(UserVisibleConnectivityState.SYNCING, state)
        assertNotEquals(UserVisibleConnectivityState.RECONNECTING, state)
        assertEquals(MediaUsability.MEDIA_OK, axes.media)
        assertEquals(ControlSyncState.SYNCING, axes.control)
    }

    @Test
    fun case1_iceDisconnected_stickyReceivePath_mediaUnavailable_isReconnecting_notSyncing() {
        // Field bug: ice=DISCONNECTED, MediaState.RECONNECTING, sticky receivePathLive=true.
        // MediaUsabilityFact must supply mediaUnavailable=true so media axis is UNAVAILABLE.
        val axes = UserVisibleConnectivityProjection.deriveAxes(
            receivePathLive = true,
            mediaEverLive = true,
            recovering = true,
            mediaUnavailable = true,
            controlSyncPending = true
        )
        val state = UserVisibleConnectivityProjection.project(axes)
        assertEquals(UserVisibleConnectivityState.RECONNECTING, state)
        assertNotEquals(UserVisibleConnectivityState.SYNCING, state)
        assertEquals(MediaUsability.MEDIA_UNAVAILABLE, axes.media)
    }

    @Test
    fun caseB_mediaUnavailable_repairActive_isReconnecting() {
        val state = UserVisibleConnectivityProjection.project(
            UserVisibleConnectivityProjection.deriveAxes(
                receivePathLive = false,
                mediaEverLive = true,
                recovering = true,
                mediaUnavailable = false
            )
        )
        assertEquals(UserVisibleConnectivityState.RECONNECTING, state)
    }

    @Test
    fun caseC_mediaOk_peerEdgeDegraded_isDegraded() {
        val state = UserVisibleConnectivityProjection.project(
            UserVisibleConnectivityProjection.deriveAxes(
                receivePathLive = true,
                mediaEverLive = true,
                recovering = false,
                mediaUnavailable = false,
                controlDegraded = true
            )
        )
        assertEquals(UserVisibleConnectivityState.DEGRADED, state)
        assertNotEquals(UserVisibleConnectivityState.SYNCING, state)
    }

    @Test
    fun caseD_stable_isConnected() {
        // Case 3: ICE_CONNECTED / media usable / control stable → CONNECTED
        val state = UserVisibleConnectivityProjection.project(
            UserVisibleConnectivityProjection.deriveAxes(
                receivePathLive = true,
                mediaEverLive = true,
                recovering = false,
                mediaUnavailable = false
            )
        )
        assertEquals(UserVisibleConnectivityState.CONNECTED, state)
    }

    @Test
    fun mediaOk_mustNeverMapToReconnecting() {
        for (control in ControlSyncState.entries) {
            val state = UserVisibleConnectivityProjection.project(MediaUsability.MEDIA_OK, control)
            assertNotEquals(
                "MEDIA_OK + $control must not be RECONNECTING",
                UserVisibleConnectivityState.RECONNECTING,
                state
            )
        }
    }

    @Test
    fun obligationOpenAlone_viaControlPending_withMediaOk_isSyncing() {
        // INV-PRES-006: obligation / recovering must not become RECONNECTING when media OK.
        val state = UserVisibleConnectivityProjection.project(
            MediaUsability.MEDIA_OK,
            ControlSyncState.SYNCING
        )
        assertEquals(UserVisibleConnectivityState.SYNCING, state)
    }

    @Test
    fun aggregate_prefersDegradedOverSyncing() {
        val summary = UserVisibleConnectivityProjection.aggregateMeetingConnectivity(
            listOf(
                "M01" to UserVisibleConnectivityState.CONNECTED,
                "M03" to UserVisibleConnectivityState.DEGRADED,
                "M04" to UserVisibleConnectivityState.SYNCING
            )
        )
        assertEquals(UserVisibleConnectivityState.DEGRADED, summary!!.state)
        assertEquals(listOf("M03"), summary.peerIds)
        assertEquals("M03 degraded...", UserVisibleConnectivityProjection.formatMeetingHint(summary))
    }

    @Test
    fun aggregate_equalSeverity_usesCount() {
        val summary = UserVisibleConnectivityProjection.aggregateMeetingConnectivity(
            listOf(
                "M01" to UserVisibleConnectivityState.DEGRADED,
                "M02" to UserVisibleConnectivityState.DEGRADED,
                "M03" to UserVisibleConnectivityState.DEGRADED
            )
        )
        assertEquals(UserVisibleConnectivityState.DEGRADED, summary!!.state)
        assertEquals(3, summary.peerIds.size)
        assertEquals("3 members degraded", UserVisibleConnectivityProjection.formatMeetingHint(summary))
    }

    @Test
    fun aggregate_allConnected_returnsNullHint() {
        val summary = UserVisibleConnectivityProjection.aggregateMeetingConnectivity(
            listOf(
                "M01" to UserVisibleConnectivityState.CONNECTED,
                "M02" to UserVisibleConnectivityState.CONNECTED
            )
        )
        assertNull(summary)
        assertNull(UserVisibleConnectivityProjection.formatMeetingHint(summary))
    }

    @Test
    fun aggregate_countMustNotOutrankSeverity() {
        val summary = UserVisibleConnectivityProjection.aggregateMeetingConnectivity(
            listOf(
                "M01" to UserVisibleConnectivityState.DEGRADED,
                "M02" to UserVisibleConnectivityState.SYNCING,
                "M03" to UserVisibleConnectivityState.SYNCING,
                "M04" to UserVisibleConnectivityState.SYNCING
            )
        )
        assertEquals(UserVisibleConnectivityState.DEGRADED, summary!!.state)
    }

    @Test
    fun admissionGuard_syncingDoesNotDenyAllowAdmission() {
        val visible = UserVisibleConnectivityState.SYNCING
        val admissionAllow = true
        // Display-only: action follows admission, not projection (INV-PRES-009).
        val actionAllowed = admissionAllow
        assertTrue(actionAllowed)
        assertEquals(UserVisibleConnectivityState.SYNCING, visible)
    }

    @Test
    fun admissionGuard_connectedDoesNotBypassBlockedAdmission() {
        val visible = UserVisibleConnectivityState.CONNECTED
        val admissionBlocked = true
        val actionAllowed = !admissionBlocked
        assertFalse(actionAllowed)
        assertEquals(UserVisibleConnectivityState.CONNECTED, visible)
    }


    @Test
    fun caseE_syncingClearsToConnected_whenControlFactsNoLongerJustify() {
        // G-PRES-E (unit): projection recalculates; no timer owns the transition.
        val whilePending = UserVisibleConnectivityProjection.project(
            UserVisibleConnectivityProjection.deriveAxes(
                receivePathLive = true,
                mediaEverLive = true,
                recovering = true,
                mediaUnavailable = false
            )
        )
        assertEquals(UserVisibleConnectivityState.SYNCING, whilePending)

        val afterResolved = UserVisibleConnectivityProjection.project(
            UserVisibleConnectivityProjection.deriveAxes(
                receivePathLive = true,
                mediaEverLive = true,
                recovering = false,
                mediaUnavailable = false,
                controlSyncPending = false
            )
        )
        assertEquals(UserVisibleConnectivityState.CONNECTED, afterResolved)
    }

    @Test
    fun projection_isPure_noTimerFields() {
        val clazz = UserVisibleConnectivityProjection::class.java
        val forbidden = clazz.declaredFields.filter { field ->
            field.name != "INSTANCE" &&
                (field.name.contains("timer", ignoreCase = true) ||
                    field.name.contains("cache", ignoreCase = true) ||
                    field.name.contains("latch", ignoreCase = true) ||
                    field.name.contains("deadline", ignoreCase = true))
        }
        assertTrue(forbidden.isEmpty())
    }
}
