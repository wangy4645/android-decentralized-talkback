package com.talkback.core.ptt

import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorOwnerTest {

    private val m01 = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
    private val m02 = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
    private val arbitrator = FloorArbitrator()

    @Test
    fun commitGrant_passThrough_appliesRemoteGrant() {
        val owner = FloorOwner()
        val outcome = owner.commitGrant(
            FloorGrantCompletion(
                owner = m02,
                floorVersion = 5L,
                floorEpoch = 2L,
                priority = EndpointPriority.EMERGENCY,
                alreadyGranted = false
            )
        )
        assertEquals(FloorCommitResult.APPLIED, outcome.result)
        assertEquals(m02, owner.floor.owner())
        assertEquals(5L, owner.floor.version())
        assertEquals(2L, owner.floor.epoch())
    }

    @Test
    fun commitGrant_passThrough_skipsMutationWhenAlreadyGranted() {
        val owner = FloorOwner()
        owner.floor.tryGrant(m01, 1, 0, EndpointPriority.NORMAL, 100, arbitrator)
        val versionBefore = owner.floor.version()
        owner.createRequestToken("sess-1", m01, versionBefore)
        val outcome = owner.commitGrant(
            FloorGrantCompletion(
                owner = m01,
                floorVersion = owner.floor.version(),
                floorEpoch = owner.floor.epoch(),
                priority = EndpointPriority.NORMAL,
                alreadyGranted = true
            ),
            requesterIntent = FloorOperationIdentity("sess-1", m01.key)
        )
        assertEquals(FloorCommitResult.APPLIED, outcome.result)
        assertEquals(versionBefore, owner.floor.version())
        assertEquals(m01, owner.floor.owner())
    }

    @Test
    fun createRequestToken_registersValidToken() {
        val owner = FloorOwner()
        val token = owner.createRequestToken("sess-1", m02, version = 4L)
        assertEquals(OperationTokenValidity.VALID, token.validity)
        assertEquals(4L, token.version)
        assertEquals(token, owner.tokens.validTokenFor(FloorOperationIdentity("sess-1", m02.key)))
    }

    @Test
    fun invalidateRequestToken_clearsValidLookup() {
        val owner = FloorOwner()
        val identity = FloorOperationIdentity("sess-1", m02.key)
        owner.createRequestToken("sess-1", m02, version = 1L)
        assertTrue(owner.invalidateRequestToken(identity))
        assertNull(owner.tokens.validTokenFor(identity))
    }

    @Test
    fun commitGrant_discardsWhenTokenInvalidated() {
        val owner = FloorOwner()
        val identity = FloorOperationIdentity("sess-1", m02.key)
        owner.createRequestToken("sess-1", m02, version = 3L)
        owner.invalidateRequestToken(identity)
        val outcome = owner.commitGrant(
            FloorGrantCompletion(
                owner = m02,
                floorVersion = 3L,
                floorEpoch = 0L,
                priority = EndpointPriority.NORMAL,
                alreadyGranted = false
            ),
            requesterIntent = identity
        )
        assertEquals(FloorCommitResult.DISCARDED, outcome.result)
        assertEquals(FloorCommitDiscardReason.TOKEN_INVALID, outcome.discardReason)
        assertNull(owner.floor.owner())
    }

    @Test
    fun commitGrant_discardsOnVersionMismatch() {
        val owner = FloorOwner()
        val identity = FloorOperationIdentity("sess-1", m02.key)
        owner.createRequestToken("sess-1", m02, version = 3L)
        val outcome = owner.commitGrant(
            FloorGrantCompletion(
                owner = m02,
                floorVersion = 1L,
                floorEpoch = 0L,
                priority = EndpointPriority.NORMAL,
                alreadyGranted = false
            ),
            requesterIntent = identity
        )
        assertEquals(FloorCommitResult.DISCARDED, outcome.result)
        assertEquals(FloorCommitDiscardReason.VERSION_MISMATCH, outcome.discardReason)
        assertNull(owner.floor.owner())
    }
}
