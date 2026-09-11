package com.talkback.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * G2-RCA2 Step 2b fixtures F6-F7 plus the closing/closed admission fence.
 *
 * [CandidateDomain] mirrors exactly how RealWebRtcAudioEngine drives the transaction:
 * the SRD, the remoteDescriptionApplied flip and the drain of the candidates it unblocks are
 * one transaction, and addIceCandidate decides queue-vs-apply inside the same transaction.
 */
class PcSignalingTransactionTest {

    private class CandidateDomain(val txn: PcSignalingTransaction = PcSignalingTransaction()) {
        @Volatile
        var remoteDescriptionApplied = false
        val queued = CopyOnWriteArrayList<String>()
        val appliedToNative = ConcurrentLinkedQueue<String>()
        val rejected = ConcurrentLinkedQueue<String>()

        fun applyRemoteAnswer(insideSrd: () -> Unit = {}) {
            txn.runOrReject(SignalingOp.APPLY_REMOTE_ANSWER, onRejected = { }) {
                insideSrd()
                remoteDescriptionApplied = true
                queued.forEach { appliedToNative.add(it) }
                queued.clear()
            }
        }

        fun addIceCandidate(candidate: String) {
            txn.runOrReject(
                SignalingOp.ADD_ICE_CANDIDATE,
                onRejected = { rejected.add(candidate) },
            ) {
                if (!remoteDescriptionApplied) queued.add(candidate) else appliedToNative.add(candidate)
            }
        }
    }

    @Test
    fun f6_candidatesArrivingDuringSrdQueueBehindTheTransactionAndAreApplied() {
        val domain = CandidateDomain()
        val srdInside = CountDownLatch(1)
        val candidatesRequested = CountDownLatch(4)
        val holdSrd = CountDownLatch(1)

        val srdThread = Thread({
            domain.applyRemoteAnswer {
                srdInside.countDown()
                holdSrd.await(5, TimeUnit.SECONDS)
            }
        }, "tb-edge-srd")
        srdThread.start()
        assertTrue(srdInside.await(3, TimeUnit.SECONDS))
        assertTrue("SRD owns the transaction", domain.txn.isActive())

        val iceThreads = (1..4).map { index ->
            Thread({
                assertTrue("candidate must see a busy transaction", domain.txn.wouldQueue())
                candidatesRequested.countDown()
                domain.addIceCandidate("cand$index")
            }, "tb-ice-$index").also { it.start() }
        }
        assertTrue(candidatesRequested.await(3, TimeUnit.SECONDS))
        Thread.sleep(80)
        assertTrue("no candidate may reach native while SRD is in flight", domain.appliedToNative.isEmpty())

        holdSrd.countDown()
        srdThread.join(3_000)
        iceThreads.forEach { it.join(3_000) }

        assertEquals(4, domain.appliedToNative.size)
        assertTrue(domain.queued.isEmpty())
        assertTrue(domain.rejected.isEmpty())
    }

    @Test
    fun f7_drainAndAddIceCandidateNeverStrandOrDuplicateACandidate() {
        repeat(40) { iteration ->
            val domain = CandidateDomain()
            val preSrdCandidates = listOf("pre-a", "pre-b")
            preSrdCandidates.forEach { domain.addIceCandidate(it) }

            val start = CountDownLatch(1)
            val racing = Thread({
                start.await(5, TimeUnit.SECONDS)
                domain.addIceCandidate("race-$iteration")
            }, "tb-ice-race")
            val srd = Thread({
                start.await(5, TimeUnit.SECONDS)
                domain.applyRemoteAnswer()
            }, "tb-edge-srd")
            racing.start()
            srd.start()
            start.countDown()
            racing.join(3_000)
            srd.join(3_000)

            val all = domain.appliedToNative.toList() + domain.queued.toList()
            assertEquals("iteration $iteration lost or duplicated a candidate", 3, all.size)
            assertEquals(3, all.distinct().size)
            // Whichever order the race resolves in, nothing may be left stranded in the queue.
            assertTrue("iteration $iteration stranded ${domain.queued}", domain.queued.isEmpty())
        }
    }

    @Test
    fun rollbackNestedInsideAnSrdTransactionIsReentrant() {
        val txn = PcSignalingTransaction()
        var nestedRan = false
        txn.runOrReject(SignalingOp.APPLY_REMOTE_ANSWER, onRejected = { }) {
            txn.runOrReject(SignalingOp.ROLLBACK, onRejected = { }) { nestedRan = true }
            assertEquals(SignalingOp.APPLY_REMOTE_ANSWER, txn.activeOperation())
        }
        assertTrue(nestedRan)
        assertFalse(txn.isActive())
    }

    @Test
    fun closeWaitsForTheInFlightTransaction() {
        val txn = PcSignalingTransaction()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val opDone = AtomicBoolean(false)

        val op = Thread({
            txn.runOrReject(SignalingOp.APPLY_REMOTE_ANSWER, onRejected = { }) {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
                opDone.set(true)
            }
        }, "tb-edge-srd")
        op.start()
        assertTrue(inside.await(3, TimeUnit.SECONDS))

        val closer = Thread({ txn.close { closed.set(true) } }, "tb-close")
        closer.start()
        Thread.sleep(80)
        assertFalse("destruction must not run while a transaction is in flight", closed.get())
        assertEquals(PcSignalingTransaction.State.CLOSING, txn.lifecycleState())

        release.countDown()
        op.join(3_000)
        closer.join(3_000)
        assertTrue(opDone.get())
        assertTrue(closed.get())
        assertEquals(PcSignalingTransaction.State.CLOSED, txn.lifecycleState())
    }

    @Test
    fun workArrivingWhileClosingIsRejectedRatherThanQueuedBehindDestruction() {
        val txn = PcSignalingTransaction()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val op = Thread({
            txn.runOrReject(SignalingOp.APPLY_REMOTE_ANSWER, onRejected = { }) {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }, "tb-edge-srd")
        op.start()
        assertTrue(inside.await(3, TimeUnit.SECONDS))

        val closer = Thread({ txn.close { Thread.sleep(50) } }, "tb-close")
        closer.start()
        while (txn.lifecycleState() == PcSignalingTransaction.State.OPEN) Thread.sleep(5)

        var ran = false
        val admission = txn.runOrReject(
            SignalingOp.ADD_ICE_CANDIDATE,
            onRejected = { it },
        ) {
            ran = true
            PcSignalingTransaction.Admission.ADMITTED
        }
        assertEquals(PcSignalingTransaction.Admission.REJECTED_CLOSING, admission)
        assertFalse("late work must never touch a PeerConnection being destroyed", ran)

        release.countDown()
        op.join(3_000)
        closer.join(3_000)

        val afterClose = txn.runOrReject(
            SignalingOp.ADD_ICE_CANDIDATE,
            onRejected = { it },
        ) { PcSignalingTransaction.Admission.ADMITTED }
        assertEquals(PcSignalingTransaction.Admission.REJECTED_CLOSED, afterClose)
    }

    @Test
    fun closeIsIdempotent() {
        val txn = PcSignalingTransaction()
        var closeCount = 0
        txn.close { closeCount++ }
        txn.close { closeCount++ }
        assertEquals(1, closeCount)
    }
}
