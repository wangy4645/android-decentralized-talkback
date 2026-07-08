package com.talkback.governance.transition

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyRegistryTest {
    @After
    fun tearDown() {
        PolicyRegistry.resetForTests()
    }
    @Test
    fun validate_acceptsDefaultPolicySnapshot() {
        val result = PolicyRegistry.validate(TransitionPolicy.buildRules())
        assertTrue(result.isValid)
    }

    @Test
    fun validate_rejectsMissingTrigger() {
        val partial = TransitionPolicy.buildRules() - TransitionTrigger.MEETING_START
        val result = PolicyRegistry.validate(partial)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("MEETING_START") })
    }

    @Test
    fun validate_rejectsMeetingStartWithoutInviteDispatch() {
        val rules = TransitionPolicy.buildRules().toMutableMap()
        rules[TransitionTrigger.MEETING_START] = TransitionPolicyRule(timeoutMs = 12_000L)
        val result = PolicyRegistry.validate(rules)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("inviteDispatch") })
    }

    @Test
    fun validate_rejectsOverlappingRetrySets() {
        val badDispatch = InviteDispatchPolicy(
            retryableErrors = setOf(InviteDispatchError.TRANSPORT_NOT_READY),
            nonRetryableErrors = setOf(InviteDispatchError.TRANSPORT_NOT_READY),
            maxRetry = 3,
            deadlineMs = 3_000L,
            backoff = InviteDispatchBackoff.EXPONENTIAL,
            initialDelayMs = 100L
        )
        val rules = TransitionPolicy.buildRules().toMutableMap()
        rules[TransitionTrigger.MEETING_START] = TransitionPolicyRule(
            timeoutMs = 12_000L,
            inviteDispatch = badDispatch
        )
        val result = PolicyRegistry.validate(rules)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("overlap") })
    }

    @Test
    fun validate_rejectsZeroTimeout() {
        val rules = TransitionPolicy.buildRules().toMutableMap()
        rules[TransitionTrigger.MEETING_END] = TransitionPolicyRule(timeoutMs = 0L)
        val result = PolicyRegistry.validate(rules)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("timeoutMs") })
    }

    @Test(expected = PolicyStartupException::class)
    fun ensureValidated_throwsOnInvalidSnapshot() {
        PolicyRegistry.ensureValidated {
            mapOf(TransitionTrigger.MEETING_START to TransitionPolicyRule(timeoutMs = 1L))
        }
    }
}
