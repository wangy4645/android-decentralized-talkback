package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-0022 §E.16.1 Slice-1 acceptance matrix for [DeferredIntentAuthority].
 */
class DeferredIntentAuthoritySlice1Test {
    private val logs = mutableListOf<String>()
    private val fenceReleases = mutableListOf<String>()
    private lateinit var authority: DeferredIntentAuthority

    private val sessionId = "sess-jx-s1"
    private val remote = "M03"

    @Before
    fun setUp() {
        logs.clear()
        fenceReleases.clear()
        authority = DeferredIntentAuthority(
            onLog = { logs.add(it) },
            clock = { 1_000L },
            onReleaseFence = { sid, rid, intentId, reason ->
                fenceReleases.add("$sid|$rid|$intentId|$reason")
            }
        )
    }

    private fun register(intentId: String = "R16") {
        authority.registerCreated(intentId, sessionId, remote, fenceArmed = true)
    }

    @Test
    fun createdToSuperseded_pass() {
        register()
        val result = authority.requestSupersede(
            intentId = "R16",
            reason = "EDGE_STARTED:ICE_DISCONNECTED",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.MEDIA,
            replacementIntentId = null
        )
        assertTrue(result is DeferredIntentAuthority.SupersedeResult.Accepted)
        assertEquals(
            DeferredIntentAuthority.ExecutionState.SUPERSEDED,
            authority.executionState("R16")
        )
        assertTrue(logs.any { it.contains("DEFERRED_INTENT_SUPERSEDED") && it.contains("oldState=CREATED") })
        assertTrue(logs.any { it.contains("FENCE_RELEASED") && it.contains("reason=SUPERSEDE") })
        assertTrue(logs.any { it.contains("ARMED_TO_RELEASED_BY_SUPERSEDE") })
        assertEquals(listOf("$sessionId|$remote|R16|RELEASED_BY_SUPERSEDE"), fenceReleases)
        assertFalse(authority.isExecutable("R16"))
    }

    @Test
    fun heldDispatchToSuperseded_pass() {
        register()
        assertTrue(authority.markHeldDispatch("R16"))
        val result = authority.requestSupersede(
            intentId = "R16",
            reason = "EDGE_STARTED:ICE_DISCONNECTED",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        assertTrue(result is DeferredIntentAuthority.SupersedeResult.Accepted)
        val accepted = result as DeferredIntentAuthority.SupersedeResult.Accepted
        assertEquals(DeferredIntentAuthority.ExecutionState.HELD_DISPATCH, accepted.oldState)
        assertTrue(logs.any { it.contains("oldState=HELD_DISPATCH") })
        assertEquals(
            DeferredIntentAuthority.ExecutionState.SUPERSEDED,
            authority.executionState("R16")
        )
    }

    @Test
    fun supersededLateEvent_auditOnly_pass() {
        register()
        authority.requestSupersede(
            "R16",
            "EDGE_STARTED:TEST",
            DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        val disposition = authority.observeLateEvent("R16", "NEGOTIATION_CAN_EXECUTE")
        assertEquals(DeferredIntentAuthority.LateEventDisposition.AUDIT_ONLY, disposition)
        assertTrue(
            logs.any {
                it.contains("DEFERRED_INTENT_LATE_EVENT_OBSERVED") &&
                    it.contains("disposition=AUDIT_ONLY")
            }
        )
        assertEquals(
            DeferredIntentAuthority.ExecutionState.SUPERSEDED,
            authority.executionState("R16")
        )
    }

    @Test
    fun supersededToExecuted_reject_pass() {
        register()
        authority.requestSupersede(
            "R16",
            "EDGE_STARTED:TEST",
            DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        assertFalse(authority.markExecuted("R16"))
        assertEquals(
            DeferredIntentAuthority.ExecutionState.SUPERSEDED,
            authority.executionState("R16")
        )
        assertTrue(logs.any { it.contains("op=MARK_EXECUTED") && it.contains("illegal_from_SUPERSEDED") })
    }

    @Test
    fun supersededToHeld_reject_pass() {
        register()
        authority.requestSupersede(
            "R16",
            "EDGE_STARTED:TEST",
            DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        assertFalse(authority.markHeldDispatch("R16"))
        assertEquals(
            DeferredIntentAuthority.ExecutionState.SUPERSEDED,
            authority.executionState("R16")
        )
        assertTrue(logs.any { it.contains("op=MARK_HELD") && it.contains("illegal_from_SUPERSEDED") })
    }

    @Test
    fun replacementEvidenceInheritance_reject_pass() {
        register("R16")
        authority.markHeldDispatch("R16")
        authority.requestSupersede(
            "R16",
            "EDGE_STARTED:TEST",
            DeferredIntentAuthority.RequestingDomain.MEDIA,
            replacementIntentId = "R17"
        )
        authority.registerCreated("R17", sessionId, remote, fenceArmed = true)
        assertFalse(authority.mayInheritDispatchEvidence("R16", "R17"))
        assertTrue(authority.isExecutable("R17"))
        assertFalse(authority.isExecutable("R16"))
    }

    @Test
    fun fenceReleaseReason_pass() {
        register()
        authority.requestSupersede(
            "R16",
            "EDGE_STARTED:ICE_DISCONNECTED",
            DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        assertTrue(fenceReleases.single().endsWith("|RELEASED_BY_SUPERSEDE"))
        assertTrue(logs.any { it.contains("FENCE_RELEASED") && it.contains("reason=SUPERSEDE") })
        assertFalse(logs.any { it.contains("FENCE_RELEASED") && it.contains("reason=null") })
    }

    @Test
    fun executedSupersede_reject_pass() {
        register()
        assertTrue(authority.markExecuted("R16"))
        val result = authority.requestSupersede(
            "R16",
            "EDGE_STARTED:TEST",
            DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        assertTrue(result is DeferredIntentAuthority.SupersedeResult.Rejected)
        assertEquals(
            "illegal_from_EXECUTED",
            (result as DeferredIntentAuthority.SupersedeResult.Rejected).reason
        )
    }

    @Test
    fun supersedeIdempotent_reject_noMutation_pass() {
        register()
        authority.requestSupersede(
            "R16",
            "EDGE_STARTED:A",
            DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        fenceReleases.clear()
        val second = authority.requestSupersede(
            "R16",
            "EDGE_STARTED:B",
            DeferredIntentAuthority.RequestingDomain.MEDIA
        )
        assertTrue(second is DeferredIntentAuthority.SupersedeResult.Rejected)
        assertEquals(
            "idempotent_already_superseded",
            (second as DeferredIntentAuthority.SupersedeResult.Rejected).reason
        )
        assertTrue(fenceReleases.isEmpty())
    }
}
