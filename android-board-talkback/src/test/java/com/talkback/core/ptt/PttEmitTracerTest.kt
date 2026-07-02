package com.talkback.core.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PttEmitTracerTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        PttEmitTracer.resetForTests { lines.add(it) }
    }

    @Test
    fun recordUiTrigger_emitsRequiredFields() {
        PttEmitTracer.recordUiTrigger("grp:CH-01", "M03", source = "UI")
        val line = lines.single()
        assertTrue(line.startsWith("PTT_UI_TRIGGER"))
        assertTrue(line.contains("sid=grp:CH-01"))
        assertTrue(line.contains("local=M03"))
        assertTrue(line.contains("source=UI"))
        assertTrue(line.contains("threadId="))
        assertTrue(line.contains("callStackHash="))
    }

    @Test
    fun recordBlocked_alsoEmitsDecision() {
        PttEmitTracer.recordBlocked(
            sessionId = "grp:CH-01",
            localModuleId = "M03",
            stage = "GATE",
            reason = "REMOTE_HOLDS_FLOOR",
            detail = "owner=M01-E01"
        )
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("PTT_EMIT_BLOCKED"))
        assertTrue(lines[1].startsWith("PTT_DECISION"))
        assertTrue(lines[1].contains("decision=blocked"))
        assertTrue(lines[1].contains("reason=REMOTE_HOLDS_FLOOR"))
    }

    @Test
    fun recordDecision_ok() {
        PttEmitTracer.recordDecision(
            sessionId = "grp:CH-01",
            localModuleId = "M02",
            ok = true,
            reason = "DISPATCH_FLOOR_REQUEST",
            traceId = 42L
        )
        val line = lines.single()
        assertTrue(line.contains("PTT_DECISION"))
        assertTrue(line.contains("decision=ok"))
        assertTrue(line.contains("traceId=42"))
    }
}
