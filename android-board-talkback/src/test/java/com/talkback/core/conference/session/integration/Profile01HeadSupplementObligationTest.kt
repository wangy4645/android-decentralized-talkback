package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile01HeadSupplementObligationTest {
    private fun snapshot(
        sessionId: String = "s1",
        version: Long = 2L,
        epoch: Long = 3L,
        digest: String = "aa",
    ) = Profile01AuthoritativeHeadSnapshot(
        conferenceIdHex = "cc",
        sessionId = sessionId,
        conferenceEpoch = 1L,
        membershipVersion = version,
        mediaKeyEpoch = epoch,
        membershipFactDigestHex = digest,
    )

    @Test
    fun openCreatesIndependentObligationPerPeer() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot()
        val opened = ledger.openForSnapshot(snap, setOf("M02", "M03", "M01"), "M01", nowMs = 10L)
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.state == Profile01HeadObligationState.OPEN })
        assertEquals(snap.headDigest, opened[0].id.headDigest)
    }

    @Test
    fun emittedAttemptDoesNotAck() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot()
        ledger.openForSnapshot(snap, setOf("M02"), "M01", 0L)
        val id = ledger.currentForPeer("s1", "M02")!!.id
        val after =
            ledger.recordAttempt(
                id = id,
                nowMs = 5L,
                sendApiResult = true,
                failure = null,
                faultAfterMs = 30_000L,
            )
        assertEquals(Profile01HeadObligationState.DELIVERING, after!!.state)
        assertTrue(after.sendApiResult == true)
        assertEquals(1, after.attemptCount)
    }

    @Test
    fun validAckCompletes() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot()
        ledger.openForSnapshot(snap, setOf("M02"), "M01", 0L)
        val acked = ledger.onReadyAck("s1", "M02", snap.headDigest, 20L)
        assertEquals(Profile01HeadObligationState.ACKED, acked!!.state)
        val again = ledger.onReadyAck("s1", "M02", snap.headDigest, 30L)
        assertEquals(Profile01HeadObligationState.ACKED, again!!.state)
        assertEquals(20L, again.ackedAtMs)
    }

    @Test
    fun staleHeadAckDoesNotCloseNewObligation() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val old = snapshot(version = 2, epoch = 3, digest = "aa")
        ledger.openForSnapshot(old, setOf("M02"), "M01", 0L)
        val next = snapshot(version = 4, epoch = 5, digest = "bb")
        ledger.openForSnapshot(next, setOf("M02"), "M01", 10L)
        assertEquals(Profile01HeadObligationState.SUPERSEDED, ledger.record(
            Profile01HeadObligationId("cc", "s1", old.headDigest, "M02"),
        )!!.state)
        val ignored = ledger.onReadyAck("s1", "M02", old.headDigest, 11L)
        assertEquals(Profile01HeadObligationState.SUPERSEDED, ignored!!.state)
        assertEquals(Profile01HeadObligationState.OPEN, ledger.currentForPeer("s1", "M02")!!.state)
        assertEquals(next.headDigest, ledger.currentForPeer("s1", "M02")!!.id.headDigest)
    }

    @Test
    fun staleHeadAck_kicksLiveHeadObligation() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val old = snapshot(version = 2, epoch = 3, digest = "aa")
        val live = snapshot(version = 4, epoch = 5, digest = "bb")
        var liveDelivers = 0
        val delayed = ArrayDeque<Pair<Long, () -> Unit>>()
        val driver =
            Profile01HeadSupplementObligationDriver(
                ledger = ledger,
                clockMs = { 0L },
                schedule = { delay, run -> delayed.addLast(delay to run) },
                deliver = { id, _ ->
                    if (id.headDigest == live.headDigest) liveDelivers++
                    Profile01HeadSupplementObligationDriver.DeliverOutcome(true, null, live.headDigest)
                },
                liveSnapshot = { live },
                random = kotlin.random.Random(0),
            )
        driver.openAndKick(live, setOf("M04"), "M01")
        delayed.removeFirst().second.invoke()
        assertEquals(1, liveDelivers)
        delayed.clear()
        driver.onStaleHeadAck("s1", "M04", old.headDigest)
        assertTrue(delayed.isNotEmpty())
        delayed.removeFirst().second.invoke()
        assertEquals(2, liveDelivers)
        assertEquals(live.headDigest, ledger.currentForPeer("s1", "M04")!!.id.headDigest)
    }

    @Test
    fun senderMismatchIsCallerResponsibility_ledgerRequiresExactPeer() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot()
        ledger.openForSnapshot(snap, setOf("M02"), "M01", 0L)
        assertNull(ledger.onReadyAck("s1", "M04", snap.headDigest, 1L))
        assertEquals(Profile01HeadObligationState.OPEN, ledger.currentForPeer("s1", "M02")!!.state)
    }

    @Test
    fun closePeerAndSession() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot()
        ledger.openForSnapshot(snap, setOf("M02", "M03"), "M01", 0L)
        ledger.closePeer("s1", "M02")
        assertEquals(
            Profile01HeadObligationState.CLOSED,
            ledger.record(Profile01HeadObligationId("cc", "s1", snap.headDigest, "M02"))!!.state,
        )
        assertNull(ledger.currentForPeer("s1", "M02"))
        ledger.closeAbsentPeers("s1", remainingPeerIds = setOf("M03"))
        assertEquals(
            Profile01HeadObligationState.CLOSED,
            ledger.record(Profile01HeadObligationId("cc", "s1", snap.headDigest, "M02"))!!.state,
        )
        assertEquals(Profile01HeadObligationState.OPEN, ledger.currentForPeer("s1", "M03")!!.state)
        ledger.closeSession("s1")
        assertTrue(ledger.unfinished("s1").isEmpty())
    }

    @Test
    fun faultDoesNotAck_andValidAckRecovers() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot()
        ledger.openForSnapshot(snap, setOf("M02"), "M01", 0L)
        val id = ledger.currentForPeer("s1", "M02")!!.id
        val faulted =
            ledger.recordAttempt(id, nowMs = 30_000L, sendApiResult = true, failure = null, faultAfterMs = 30_000L)
        assertEquals(Profile01HeadObligationState.FAULT, faulted!!.state)
        assertEquals(30_000L, faulted.faultAtMs)
        val stillFault =
            ledger.recordAttempt(id, nowMs = 34_000L, sendApiResult = false, failure = "SEND_FAILED", faultAfterMs = 30_000L)
        assertEquals(Profile01HeadObligationState.FAULT, stillFault!!.state)
        assertEquals(30_000L, stillFault.faultAtMs)
        val recovered = ledger.onReadyAck("s1", "M02", snap.headDigest, 35_000L)
        assertEquals(Profile01HeadObligationState.ACKED, recovered!!.state)
        assertTrue(recovered.recoveredFromFault)
    }

    @Test
    fun ensureUnackedOpensWhenMissing_andDoesNotRescheduleActiveHead() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot()
        var opens = 0
        val delayed = ArrayDeque<Pair<Long, () -> Unit>>()
        val driver =
            Profile01HeadSupplementObligationDriver(
                ledger = ledger,
                clockMs = { 0L },
                schedule = { delay, run ->
                    if (delay == 0L) opens++
                    delayed.addLast(delay to run)
                },
                deliver = { _, _ ->
                    Profile01HeadSupplementObligationDriver.DeliverOutcome(true, null, snap.headDigest)
                },
                liveSnapshot = { snap },
                random = kotlin.random.Random(0),
            )
        driver.ensureUnackedCurrentHeadKicking(snap, "M02", "M01")
        assertEquals(1, opens)
        driver.ensureUnackedCurrentHeadKicking(snap, "M02", "M01")
        assertEquals(1, opens)
        delayed.removeFirst().second.invoke()
        assertEquals(Profile01HeadObligationState.DELIVERING, ledger.currentForPeer("s1", "M02")!!.state)
        driver.ensureUnackedCurrentHeadKicking(snap, "M02", "M01")
        assertEquals(1, opens)
        ledger.onReadyAck("s1", "M02", snap.headDigest, 1L)
        driver.ensureUnackedCurrentHeadKicking(snap, "M02", "M01")
        assertEquals(1, opens)
        assertEquals(Profile01HeadObligationState.ACKED, ledger.currentForPeer("s1", "M02")!!.state)
    }

    @Test
    fun ensureUnackedOnNewHeadSupersedesOld_andAckOnOldDoesNotStopNew() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val old = snapshot(version = 2, epoch = 3, digest = "aa")
        val next = snapshot(version = 4, epoch = 5, digest = "bb")
        var live = old
        val delayed = ArrayDeque<Pair<Long, () -> Unit>>()
        val driver =
            Profile01HeadSupplementObligationDriver(
                ledger = ledger,
                clockMs = { 0L },
                schedule = { _, run -> delayed.addLast(0L to run) },
                deliver = { _, snap ->
                    Profile01HeadSupplementObligationDriver.DeliverOutcome(true, null, snap.headDigest)
                },
                liveSnapshot = { live },
                random = kotlin.random.Random(0),
            )
        driver.ensureUnackedCurrentHeadKicking(old, "M02", "M01")
        delayed.removeFirst().second.invoke()
        live = next
        driver.ensureUnackedCurrentHeadKicking(next, "M02", "M01")
        assertEquals(
            Profile01HeadObligationState.SUPERSEDED,
            ledger.record(Profile01HeadObligationId("cc", "s1", old.headDigest, "M02"))!!.state,
        )
        assertEquals(next.headDigest, ledger.currentForPeer("s1", "M02")!!.id.headDigest)
        ledger.onReadyAck("s1", "M02", old.headDigest, 2L)
        assertEquals(Profile01HeadObligationState.OPEN, ledger.currentForPeer("s1", "M02")!!.state)
        delayed.last { it.first == 0L }.second.invoke()
        assertEquals(Profile01HeadObligationState.DELIVERING, ledger.currentForPeer("s1", "M02")!!.state)
        ledger.onReadyAck("s1", "M02", next.headDigest, 3L)
        assertEquals(Profile01HeadObligationState.ACKED, ledger.currentForPeer("s1", "M02")!!.state)
    }

    @Test
    fun driverRetriesUntilAckAndBypassesPublishedSkip() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot()
        var sends = 0
        val delayed = ArrayDeque<Pair<Long, () -> Unit>>()
        var now = 0L
        val driver =
            Profile01HeadSupplementObligationDriver(
                ledger = ledger,
                clockMs = { now },
                schedule = { delay, run -> delayed.addLast(delay to run) },
                deliver = { _, _ ->
                    sends++
                    Profile01HeadSupplementObligationDriver.DeliverOutcome(true, null, snap.headDigest)
                },
                liveSnapshot = { snap },
                random = kotlin.random.Random(0),
                faultAfterMs = 30_000L,
            )
        driver.openAndKick(snap, setOf("M02"), "M01")
        assertEquals(1, delayed.size)
        delayed.removeFirst().second.invoke()
        assertEquals(1, sends)
        assertEquals(Profile01HeadObligationState.DELIVERING, ledger.currentForPeer("s1", "M02")!!.state)
        delayed.removeFirst().second.invoke()
        assertEquals(2, sends)
        ledger.onReadyAck("s1", "M02", snap.headDigest, now)
        val remaining = delayed.size
        delayed.removeFirst().second.invoke()
        assertEquals(remaining - 1, delayed.size)
        assertEquals(2, sends)
    }

    @Test
    fun nackIgnoredWhenAcked_andOldEpochIgnored() {
        val ledger = Profile01HeadSupplementObligationLedger()
        val snap = snapshot(epoch = 5)
        var sends = 0
        val delayed = ArrayDeque<Pair<Long, () -> Unit>>()
        val driver =
            Profile01HeadSupplementObligationDriver(
                ledger = ledger,
                clockMs = { 0L },
                schedule = { delay, run -> delayed.addLast(delay to run) },
                deliver = { _, _ ->
                    sends++
                    Profile01HeadSupplementObligationDriver.DeliverOutcome(true, null, snap.headDigest)
                },
                liveSnapshot = { snap },
                random = kotlin.random.Random(0),
            )
        driver.openAndKick(snap, setOf("M02"), "M01")
        delayed.removeFirst().second.invoke()
        ledger.onReadyAck("s1", "M02", snap.headDigest, 1L)
        delayed.clear()
        driver.onNack("s1", "M02", mediaKeyEpoch = 5)
        assertTrue(delayed.isEmpty())
        driver.onNack("s1", "M02", mediaKeyEpoch = 3)
        assertTrue(delayed.isEmpty())
        assertEquals(1, sends)
    }

    @Test
    fun readyAckRoundTrip() {
        val snap = snapshot()
        val text = Profile01HeadSupplementReadyAck.encode("s1", snap, "M05")
        val decoded = Profile01HeadSupplementReadyAck.decode(text)!!
        assertEquals("s1", decoded.sessionId)
        assertEquals("M05", decoded.peerModuleId)
        assertEquals("READY", decoded.status)
        assertEquals(snap.headDigest, decoded.headDigest)
        assertNull(Profile01HeadSupplementReadyAck.decode("//P01/MKR/s1|3"))
    }

    @Test
    fun peerPendingSurvivesSingleInstallEvent_andNewHeadReplaces() {
        val pending = Profile01PeerHeadSupplementAckPending()
        val first = snapshot(epoch = 3, digest = "aa")
        pending.noteInstalled(first, "M05")
        pending.markAttempt("s1", 10L)
        assertEquals(1, pending.current("s1")!!.attemptCount)
        val same = pending.replaceIfNewerHead(first, "M05")
        assertEquals(1, same.attemptCount)
        val newer = snapshot(epoch = 5, digest = "bb")
        val replaced = pending.replaceIfNewerHead(newer, "M05")
        assertEquals(0, replaced.attemptCount)
        assertNotEquals(first.headDigest, replaced.snapshot.headDigest)
        pending.clearSession("s1")
        assertNull(pending.current("s1"))
    }

    @Test
    fun peerPendingDropsStaleHeadAndUninstalledCurrent() {
        val pending = Profile01PeerHeadSupplementAckPending()
        val old = snapshot(epoch = 2, digest = "aa")
        val live = snapshot(epoch = 5, digest = "bb")
        pending.noteInstalled(old, "M02")
        assertNull(
            pending.dropUnlessCurrentHeadReady(
                sessionId = "s1",
                liveHeadDigest = live.headDigest,
                currentHeadSupplementInstalled = false,
            ),
        )
        assertNull(pending.current("s1"))
        pending.noteInstalled(live, "M02")
        assertNull(
            pending.dropUnlessCurrentHeadReady(
                sessionId = "s1",
                liveHeadDigest = live.headDigest,
                currentHeadSupplementInstalled = false,
            ),
        )
        pending.noteInstalled(live, "M02")
        val kept =
            pending.dropUnlessCurrentHeadReady(
                sessionId = "s1",
                liveHeadDigest = live.headDigest,
                currentHeadSupplementInstalled = true,
            )
        assertEquals(live.headDigest, kept!!.snapshot.headDigest)
    }

    @Test
    fun headDigestStableAndDistinct() {
        val a = snapshot(version = 2, epoch = 3, digest = "aa")
        val b = snapshot(version = 2, epoch = 3, digest = "aa")
        val c = snapshot(version = 4, epoch = 5, digest = "bb")
        assertEquals(a.headDigest, b.headDigest)
        assertNotEquals(a.headDigest, c.headDigest)
        assertTrue(a.headDigest.contains("|"))
    }
}
