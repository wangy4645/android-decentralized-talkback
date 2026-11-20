package com.talkback.core.channeltask

import com.talkback.core.model.ChannelTaskPhase
import com.talkback.core.model.TaskAckPayload
import com.talkback.core.model.TaskIssuePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTaskControllerTest {
    @Test
    fun duplicateIssueResendsAckWhenAlreadyAcked() {
        val c = ChannelTaskController()
        val payload = issue()
        c.onIssue("M01", payload, "M03", nowMs = 1L)
        c.ackLocal("t1", "M03", nowMs = 2L)
        val again = c.onIssue("M01", payload, "M03", nowMs = 3L)
        assertTrue(again is ChannelTaskController.IssueResult.DuplicateResendAck)
    }

    @Test
    fun ackUsesFromModuleNotSelfReportedId() {
        val c = ChannelTaskController()
        c.createLocalIssue("M01", issue())
        c.onAck("M03", TaskAckPayload("t1"), nowMs = 1L)
        val snap = c.snapshot("t1", 1L, "M01")!!
        assertEquals(ChannelTaskAckStatus.ACKED, snap.members.first { it.moduleId == "M03" }.status)
        assertEquals(2, snap.ackedCount)
    }

    @Test
    fun emptyRetryAfterDeadline() {
        val c = ChannelTaskController()
        c.createLocalIssue("M01", issue(deadline = 10L))
        assertTrue(c.pendingRetryTargets("t1", nowMs = 11L).isEmpty())
        assertEquals(ChannelTaskPhase.EXPIRED, c.snapshot("t1", 11L, "M01")!!.phase)
    }

    private fun issue(deadline: Long = 9_999_999_999L) = TaskIssuePayload(
        taskId = "t1",
        channelId = "CH-01",
        title = "Check north",
        body = "",
        deadlineEpochMs = deadline,
        targets = listOf("M01", "M03"),
    )
}
