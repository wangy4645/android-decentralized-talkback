package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConferenceEdgeRecoveryControllerTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var reattachCalls = 0
    private var iceRestartCalls = 0
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        reattachCalls = 0
        iceRestartCalls = 0
        decisionLogs.clear()
        controller = ConferenceEdgeRecoveryController(
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 500L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { message -> decisionLogs.add(message) },
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                true
            },
            onIceRestart = { _, _ ->
                iceRestartCalls++
                true
            }
        )
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    @Test
    fun participantHostDisconnect_triggersReattachAfterDebounce() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        assertEquals(0, reattachCalls)
        nowMs = 60L
        Thread.sleep(80)
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertEquals(1, reattachCalls)
    }

    @Test
    fun hostWaitsForInboundReattach_withoutSending() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        nowMs = 60L
        Thread.sleep(80)
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertEquals(0, reattachCalls)
    }

    @Test
    fun reattachAccepted_issuesSingleIceRestart() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        controller.onReattachAccepted("sess-1", "M02")
        assertEquals(1, iceRestartCalls)
        controller.onReattachAccepted("sess-1", "M02")
        assertEquals(1, iceRestartCalls)
    }

    @Test
    fun terminationCancelsPendingRecovery() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        controller.cancelChannel("CH-1", "CONFERENCE_TERMINATED")
        nowMs = 100L
        Thread.sleep(80)
        assertEquals(0, reattachCalls)
        assertFalse(controller.factsForSession("sess-1").anyRecovering)
    }

    @Test
    fun ineligibleRemoteLeft_logsRecoveryDecisionRejected() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "FAILED",
            eligibility = EdgeRecoveryEligibility(
                lifecycleEstablished = true,
                localJoined = true,
                remoteJoined = false,
                conferenceTerminated = false
            ),
            initiatesReattach = false
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_DECISION") &&
                    it.contains("terminationReason=USER_LEAVE") &&
                    it.contains("approved=false")
            }
        )
    }

    @Test
    fun iceConnected_marksRecovered() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        assertEquals(1, reattachCalls)
        controller.onIceConnected("sess-1", "M01")
        assertFalse(controller.factsForSession("sess-1").anyRecovering)
    }
}
