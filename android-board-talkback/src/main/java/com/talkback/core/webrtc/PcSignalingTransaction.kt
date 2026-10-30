package com.talkback.core.webrtc

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * G2-RCA2 Step 2b: per-PeerConnection signaling transaction domain.
 *
 * Every mutating native signaling call for one PeerConnection runs as a serialized
 * transaction: createOffer / setLocalDescription / setRemoteDescription / addIceCandidate /
 * queued-candidate drain / rollback, plus close+dispose. An SRD and the drain of the
 * candidates it unblocks belong to the same transaction, so no candidate can be applied
 * against a description that is still being installed.
 *
 * Read-only native probes invoked from WebRTC observer callbacks (getStats, signalingState,
 * description reads) are deliberately NOT admitted here: the signaling thread runs those
 * callbacks while an edge executor owns the transaction, so admitting them would deadlock.
 */
internal class PcSignalingTransaction {

    enum class State { OPEN, CLOSING, CLOSED }

    enum class Admission { ADMITTED, REJECTED_CLOSING, REJECTED_CLOSED }

    private val lock = ReentrantLock(true)
    private val state = AtomicReference(State.OPEN)

    @Volatile
    private var activeOp: SignalingOp? = null

    @Volatile
    private var activeOwnerThread: String? = null

    fun lifecycleState(): State = state.get()

    fun isActive(): Boolean = activeOp != null

    fun activeOperation(): SignalingOp? = activeOp

    /** Thread currently owning the transaction, or null when idle. */
    fun transactionOwner(): String? = activeOwnerThread

    /** True when another thread already owns the transaction, i.e. this caller will queue. */
    fun wouldQueue(): Boolean {
        val owner = activeOwnerThread ?: return false
        return owner != Thread.currentThread().name
    }

    /**
     * Runs [block] as a transaction. Reentrant: rollback nested inside an SRD transaction
     * joins the enclosing transaction instead of deadlocking.
     *
     * The closing/closed fence is checked twice — once before queueing and once after the
     * lock is granted — so work that was already blocked behind close/dispose is rejected
     * rather than allowed to touch a destroyed native PeerConnection by FIFO order alone.
     */
    fun <T> runOrReject(op: SignalingOp, onRejected: (Admission) -> T, block: () -> T): T {
        val reentrant = lock.isHeldByCurrentThread
        if (!reentrant) {
            rejectionFor(state.get())?.let { return onRejected(it) }
        }
        lock.lock()
        try {
            if (!reentrant) {
                rejectionFor(state.get())?.let { return onRejected(it) }
            }
            val previousOp = activeOp
            val previousOwner = activeOwnerThread
            activeOp = op
            activeOwnerThread = Thread.currentThread().name
            try {
                return block()
            } finally {
                activeOp = previousOp
                activeOwnerThread = previousOwner
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Closes the transaction domain. Admission flips to CLOSING before the lock is requested,
     * so callers that arrive after this point never queue behind destruction. [block] runs
     * once the in-flight transaction (if any) has drained. Idempotent.
     */
    fun close(block: () -> Unit) {
        if (!state.compareAndSet(State.OPEN, State.CLOSING)) return
        lock.lock()
        try {
            activeOp = SignalingOp.CLOSE
            activeOwnerThread = Thread.currentThread().name
            block()
        } finally {
            state.set(State.CLOSED)
            activeOp = null
            activeOwnerThread = null
            lock.unlock()
        }
    }

    private fun rejectionFor(current: State): Admission? = when (current) {
        State.OPEN -> null
        State.CLOSING -> Admission.REJECTED_CLOSING
        State.CLOSED -> Admission.REJECTED_CLOSED
    }
}

internal enum class SignalingOp {
    CREATE_OFFER,
    APPLY_REMOTE_OFFER,
    APPLY_REMOTE_ANSWER,
    ADD_ICE_CANDIDATE,
    ROLLBACK,
    CLOSE,
}
