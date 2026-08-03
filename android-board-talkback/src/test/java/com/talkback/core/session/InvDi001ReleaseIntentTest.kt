package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-0022 section E.16.4 Grill R2 / INV-DI-001 — [DeferredIntentAuthority.releaseIntent] contract.
 */
class InvDi001ReleaseIntentTest {
    private val logs = mutableListOf<String>()
    private val fenceReleases = mutableListOf<String>()
    private lateinit var authority: DeferredIntentAuthority

    private val sessionId = "sess-inv-di-001"
    private val remote = "M03"

    @Before
    fun setUp() {
        logs.clear()
        fenceReleases.clear()
        authority = DeferredIntentAuthority(
            onLog = { logs.add(it) },
            clock = { 2_000L },
            onReleaseFence = { sid, rid, intentId, reason ->
                fenceReleases.add("$sid|$rid|$intentId|$reason")
            }
        )
    }

    private fun register(intentId: String = "R16") {
        authority.registerCreated(intentId, sessionId, remote, fenceArmed = true)
    }

    @Test
    fun terminalDiscard_fromHeld_supersedesAndReleasesFence() {
        register()
        assertTrue(authority.markHeldDispatch("R16"))
        val result = authority.releaseIntent(
            intentId = "R16",
            reason = "DRAIN_STALE_LINEAGE",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.NEGOTIATION,
            kind = DeferredIntentAuthority.ReleaseKind.TERMINAL_DISCARD,
            expireCause = "DRAIN_STALE_LINEAGE"
        )
        assertTrue(result is DeferredIntentAuthority.ReleaseResult.Accepted)
        assertEquals(
            DeferredIntentAuthority.ExecutionState.SUPERSEDED,
            authority.executionState("R16")
        )
        assertTrue(logs.any { it.contains("RECOVERY_ICE_RESTART_INTENT_TERMINAL") && it.contains("expireCause=DRAIN_STALE_LINEAGE") })
        assertTrue(logs.any { it.contains("DEFERRED_INTENT_SUPERSEDED") && it.contains("oldState=HELD_DISPATCH") })
        assertEquals(listOf("$sessionId|$remote|R16|RELEASED_BY_SUPERSEDE"), fenceReleases)
        assertFalse(authority.isExecutable("R16"))
    }

    @Test
    fun slotAfterExecuted_requiresExecutedState() {
        register()
        assertTrue(authority.markHeldDispatch("R16"))
        val rejected = authority.releaseIntent(
            intentId = "R16",
            reason = "DRAIN_AFTER_NEGOTIATION_CAN_EXECUTE",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.NEGOTIATION,
            kind = DeferredIntentAuthority.ReleaseKind.SLOT_AFTER_EXECUTED
        )
        assertTrue(rejected is DeferredIntentAuthority.ReleaseResult.Rejected)
        assertEquals(
            "not_executed",
            (rejected as DeferredIntentAuthority.ReleaseResult.Rejected).reason
        )
        assertTrue(authority.markExecuted("R16"))
        val accepted = authority.releaseIntent(
            intentId = "R16",
            reason = "DRAIN_AFTER_NEGOTIATION_CAN_EXECUTE",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.NEGOTIATION,
            kind = DeferredIntentAuthority.ReleaseKind.SLOT_AFTER_EXECUTED
        )
        assertTrue(accepted is DeferredIntentAuthority.ReleaseResult.Accepted)
        assertEquals(
            DeferredIntentAuthority.ExecutionState.EXECUTED,
            (accepted as DeferredIntentAuthority.ReleaseResult.Accepted).terminalState
        )
        assertTrue(logs.any { it.contains("DEFERRED_INTENT_RELEASED") && it.contains("terminal=EXECUTED") })
    }

    @Test
    fun supersedeRelease_afterPriorSupersede_acceptsSlotRelease() {
        register()
        authority.requestSupersede(
            "R16",
            "EDGE_STARTED:ICE_DISCONNECTED",
            DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        fenceReleases.clear()
        val result = authority.releaseIntent(
            intentId = "R16",
            reason = "EDGE_STARTED:ICE_DISCONNECTED",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.MEDIA,
            kind = DeferredIntentAuthority.ReleaseKind.SUPERSEDE
        )
        assertTrue(result is DeferredIntentAuthority.ReleaseResult.Accepted)
        assertEquals(
            DeferredIntentAuthority.ExecutionState.SUPERSEDED,
            (result as DeferredIntentAuthority.ReleaseResult.Accepted).terminalState
        )
        assertTrue(fenceReleases.isEmpty())
        assertTrue(logs.any { it.contains("DEFERRED_INTENT_RELEASED") && it.contains("terminal=SUPERSEDED") })
    }

    @Test
    fun noAuthorityRecord_returnsOrphanCleanupPath() {
        val result = authority.releaseIntent(
            intentId = "R99",
            reason = "ORPHAN",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.CONTROL,
            kind = DeferredIntentAuthority.ReleaseKind.TERMINAL_DISCARD
        )
        assertTrue(result is DeferredIntentAuthority.ReleaseResult.NoAuthorityRecord)
        assertTrue(logs.any { it.contains("DEFERRED_INTENT_RELEASE_NO_AUTHORITY") && it.contains("intentId=R99") })
    }

    @Test
    fun executedTerminalDiscard_rejected() {
        register()
        assertTrue(authority.markExecuted("R16"))
        val result = authority.releaseIntent(
            intentId = "R16",
            reason = "DRAIN_OBLIGATION_CLOSED",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.NEGOTIATION,
            kind = DeferredIntentAuthority.ReleaseKind.TERMINAL_DISCARD,
            expireCause = "DRAIN_OBLIGATION_CLOSED"
        )
        assertTrue(result is DeferredIntentAuthority.ReleaseResult.Rejected)
        assertEquals(
            "illegal_from_EXECUTED",
            (result as DeferredIntentAuthority.ReleaseResult.Rejected).reason
        )
    }
}
