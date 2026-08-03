package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SuppressSuccessorAttemptDebugInjectionTest {

    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        SuppressSuccessorAttemptDebugInjection.resetForTest { logs += it }
        logs.clear()
    }

    @After
    fun tearDown() {
        SuppressSuccessorAttemptDebugInjection.resetForTest()
    }

    @Test
    fun arm_emitsArmedOnly_withoutApplied() {
        SuppressSuccessorAttemptDebugInjection.arm(
            sessionId = "sess-1",
            targetModule = "M03",
            ttlMs = 60_000L,
            reason = "UT",
            nowMs = 1_000L
        )
        assertTrue(logs.any { it.contains("SUPPRESS_SUCCESSOR_ATTEMPT_ARMED") })
        assertFalse(logs.any { it.contains("SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED") })
        assertTrue(
            SuppressSuccessorAttemptDebugInjection.isArmed("sess-1", "M03", nowMs = 1_500L)
        )
    }

    @Test
    fun trySuppress_whenArmed_emitsAppliedAndBlocks() {
        SuppressSuccessorAttemptDebugInjection.arm(
            sessionId = "sess-1",
            targetModule = "M03",
            ttlMs = 60_000L,
            reason = "UT",
            nowMs = 1_000L
        )
        logs.clear()
        val blocked = SuppressSuccessorAttemptDebugInjection.trySuppressAdmission(
            sessionId = "sess-1",
            remoteModuleId = "M03",
            originalAttemptId = 7L,
            generation = 1L,
            nowMs = 2_000L
        )
        assertTrue(blocked)
        assertEquals(1, SuppressSuccessorAttemptDebugInjection.applyCount())
        assertTrue(logs.any { it.contains("SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED") })
        assertTrue(logs.any { it.contains("originalAttemptId=7") })
        assertTrue(logs.any { it.contains("generation=1") })
        assertTrue(logs.any { it.contains("HARNESS_SUCCESSOR_SUPPRESSION_APPLIED") })
    }

    @Test
    fun trySuppress_whenExpired_doesNotBlock_emitsExpired() {
        SuppressSuccessorAttemptDebugInjection.arm(
            sessionId = "sess-1",
            targetModule = "M03",
            ttlMs = 100L,
            reason = "UT",
            nowMs = 1_000L
        )
        logs.clear()
        val blocked = SuppressSuccessorAttemptDebugInjection.trySuppressAdmission(
            sessionId = "sess-1",
            remoteModuleId = "M03",
            originalAttemptId = 1L,
            generation = 1L,
            nowMs = 1_200L
        )
        assertFalse(blocked)
        assertEquals(0, SuppressSuccessorAttemptDebugInjection.applyCount())
        assertTrue(logs.any { it.contains("SUPPRESS_SUCCESSOR_ATTEMPT_EXPIRED") })
        assertFalse(logs.any { it.contains("SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED") })
    }

    @Test
    fun trySuppress_whenNotArmed_isNoOp() {
        assertFalse(
            SuppressSuccessorAttemptDebugInjection.trySuppressAdmission(
                sessionId = "sess-1",
                remoteModuleId = "M03",
                originalAttemptId = 1L,
                generation = 1L,
                nowMs = 1L
            )
        )
        assertTrue(logs.isEmpty())
    }
    @Test
    fun suppress_doesNotEmitAdoptionOrTransferFacts() {
        SuppressSuccessorAttemptDebugInjection.arm(
            sessionId = "sess-1",
            targetModule = "M03",
            ttlMs = 60_000L,
            reason = "UT",
            nowMs = 1_000L
        )
        logs.clear()
        assertTrue(
            SuppressSuccessorAttemptDebugInjection.trySuppressAdmission(
                sessionId = "sess-1",
                remoteModuleId = "M03",
                originalAttemptId = 3L,
                generation = 1L,
                nowMs = 2_000L
            )
        )
        val joined = logs.joinToString("\n")
        assertFalse(joined.contains("SUCCESSOR_OBLIGATION_ADOPTED"))
        assertFalse(joined.contains("TRANSFERRED"))
        assertFalse(joined.contains("SUCCESSOR_SUPPRESSED"))
        assertTrue(joined.contains("HARNESS_SUCCESSOR_SUPPRESSION_APPLIED"))
        assertTrue(joined.contains("namespace=HARNESS_ONLY"))
    }
}