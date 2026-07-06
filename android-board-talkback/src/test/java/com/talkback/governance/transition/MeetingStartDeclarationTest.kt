package com.talkback.governance.transition

import com.talkback.core.model.EndpointId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingStartDeclarationTest {
    @Test
    fun soloHost_requiresEmptyTargets() {
        val decl = MeetingStartDeclaration.open(MeetingMode.SOLO_HOST, emptySet())
        assertNotNull(decl)
        assertEquals(MeetingMode.SOLO_HOST, decl!!.mode)
        assertTrue(decl.expectedInviteTargets.isEmpty())
        assertFalse(decl.isFrozen)
    }

    @Test
    fun multiParty_requiresNonEmptyTargets() {
        val targets = setOf(EndpointId("E02"), EndpointId("E03"))
        val decl = MeetingStartDeclaration.open(MeetingMode.MULTI_PARTY, targets)
        assertNotNull(decl)
        assertEquals(targets, decl!!.expectedInviteTargets)
    }

    @Test
    fun inconsistentMode_rejected() {
        assertNull(
            MeetingStartDeclaration.open(MeetingMode.SOLO_HOST, setOf(EndpointId("E02")))
        )
        assertNull(
            MeetingStartDeclaration.open(MeetingMode.MULTI_PARTY, emptySet())
        )
    }

    @Test
    fun freeze_makesDeclarationImmutablePhase() {
        val window = MeetingStartDeclarationWindow()
        val opened = window.open(MeetingMode.MULTI_PARTY, setOf(EndpointId("E02")))
        assertNotNull(opened)

        val frozen = window.freeze(inviteDispatchFinished = true)
        assertNotNull(frozen)
        assertTrue(frozen!!.isFrozen)
        assertTrue(frozen.inviteDispatchFinished)
        assertNull(window.freeze(inviteDispatchFinished = true))
    }

    @Test
    fun window_openOnlyOnce() {
        val window = MeetingStartDeclarationWindow()
        assertNotNull(window.open(MeetingMode.SOLO_HOST, emptySet()))
        assertNull(window.open(MeetingMode.SOLO_HOST, emptySet()))
    }
}
