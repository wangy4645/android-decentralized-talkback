package com.talkback.core.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorOperationTokenRegistryTest {

    private val registry = FloorOperationTokenRegistry()
    private val identity = FloorOperationIdentity("sess-1", "M02-E01")

    @Test
    fun create_returnsValidToken() {
        val token = registry.create(identity, version = 3L)
        assertEquals(OperationTokenValidity.VALID, token.validity)
        assertEquals(3L, token.version)
        assertEquals(identity, token.identity)
    }

    @Test
    fun invalidate_marksTerminal() {
        val token = registry.create(identity, version = 1L)
        assertTrue(registry.invalidate(token))
        assertEquals(OperationTokenValidity.INVALIDATED, registry.lookup(identity)?.validity)
        assertTrue(registry.lookup(identity)!!.isTerminal())
    }

    @Test
    fun complete_marksTerminal() {
        val token = registry.create(identity, version = 1L)
        assertTrue(registry.complete(token))
        assertEquals(OperationTokenValidity.COMPLETED, registry.lookup(identity)?.validity)
    }

    @Test
    fun terminalTokens_cannotTransitionAgain() {
        val token = registry.create(identity, version = 1L)
        registry.invalidate(token)
        assertFalse(registry.invalidate(token))
        assertFalse(registry.complete(token))
    }

    @Test
    fun completedToken_cannotInvalidate() {
        val token = registry.create(identity, version = 1L)
        registry.complete(token)
        assertFalse(registry.invalidate(token))
    }

    @Test
    fun newPttDown_supersedesPreviousValidToken() {
        registry.create(identity, version = 1L)
        val second = registry.create(identity, version = 2L)
        assertEquals(OperationTokenValidity.VALID, second.validity)
        assertEquals(2L, registry.lookup(identity)?.version)
        assertEquals(second, registry.validTokenFor(identity))
    }

    @Test
    fun lookup_returnsNullWhenNoToken() {
        assertNull(registry.lookup(identity))
    }

    @Test
    fun validTokenForRequester_filtersByValidity() {
        registry.create(identity, version = 1L)
        assertNotNull(registry.validTokenFor(identity))
        registry.invalidate(registry.lookup(identity)!!)
        assertNull(registry.validTokenFor(identity))
    }

    @Test
    fun recordWithdrawal_blocksMatchingRequestVersion() {
        registry.create(identity, version = 2L)
        registry.recordWithdrawal(identity, version = 2L)
        assertTrue(registry.isWithdrawn(identity, version = 2L))
        assertNull(registry.validTokenFor(identity))
        assertFalse(registry.isWithdrawn(identity, version = 3L))
    }
}
