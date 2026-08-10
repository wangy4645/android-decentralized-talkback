package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RCA-001: PARTICIPANT_REATTACH must not veto HOST_RESTART (EP1 field reject).
 *
 * ```
 * PARTICIPANT_REATTACH + HOST_RESTART → SUPERSEDED (not REJECTED)
 * ```
 */
class Rca001MediaActionOwnerSupersedeTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var reattachCalls = 0
    private var nextOutcome: () -> ReattachDispatchOutcome = { ReattachDispatchOutcome.SENT }
    private val gateOpen = AtomicBoolean(true)
    private val logs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        reattachCalls = 0
        nextOutcome = { ReattachDispatchOutcome.SENT }
        gateOpen.set(true)
        logs.clear()
        controller = ConferenceEdgeRecoveryController(
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 2_000L,
            observationWindowMs = 10_000L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { logs.add(it) },
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                nextOutcome()
            },
            onIceRestart = { _, _ -> true },
            isIceConnected = { _, _ -> false },
            canDispatchRecoveryMediaAction = { _, _ -> gateOpen.get() },
            membershipEpochProbe = DefaultOpenMembershipAuthoritySentinel
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
    fun hostRestart_supersedesParticipantReattach_precedence() {
        assertTrue(
            MediaActionOwner.PARTICIPANT_REATTACH.canBeSupersededBy(MediaActionOwner.HOST_RESTART)
        )
        assertFalse(
            MediaActionOwner.HOST_RESTART.canBeSupersededBy(MediaActionOwner.PARTICIPANT_REATTACH)
        )
        assertFalse(
            MediaActionOwner.PARTICIPANT_REATTACH.canBeSupersededBy(MediaActionOwner.PARTICIPANT_REATTACH)
        )
    }

    @Test
    fun ep1_sendFailedThenSent_hostRestartSupersedesParticipant() {
        // Field EP1: PARTICIPANT_REATTACH held, then HOST_RESTART requested (was REJECTED).
        var round = 0
        nextOutcome = {
            round++
            if (round == 1) ReattachDispatchOutcome.SEND_FAILED else ReattachDispatchOutcome.SENT
        }
        controller.onIceStateChanged(
            sessionId = "sess-ep1",
            channelId = "CH-01",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)

        assertTrue(
            logs.any {
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") &&
                    it.contains("owner=PARTICIPANT_REATTACH")
            }
        )

        controller.registerReattachTransportNonce("sess-ep1", "M01", "nonce-ep1")
        nowMs = 300L
        Thread.sleep(250)

        assertTrue(reattachCalls >= 2)
        assertTrue(
            "expected SUPERSEDED handoff",
            logs.any {
                it.contains("RECOVERY_MEDIA_OWNER_SUPERSEDED") &&
                    it.contains("existing=PARTICIPANT_REATTACH") &&
                    it.contains("requested=HOST_RESTART")
            }
        )
        assertFalse(
            "must not REJECT HOST_RESTART while holding PARTICIPANT_REATTACH",
            logs.any {
                it.contains("RECOVERY_MEDIA_OWNER_REJECTED") &&
                    it.contains("existing=PARTICIPANT_REATTACH") &&
                    it.contains("requested=HOST_RESTART")
            }
        )
        assertTrue(
            logs.any {
                it.contains("RECOVERY_MEDIA_OWNER_ASSIGNED") &&
                    it.contains("owner=HOST_RESTART") &&
                    it.contains("remote=M01")
            }
        )
    }
}
