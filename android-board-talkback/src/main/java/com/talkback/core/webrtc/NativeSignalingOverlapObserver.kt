package com.talkback.core.webrtc

import com.talkback.core.util.TalkbackLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * G2-RCA2 Step 2b probe C: records native signaling work that races an in-flight SRD.
 *
 * This probe survives the transaction-queue fix on purpose. Before the fix an overlap meant two
 * threads inside the shared factory's signaling domain at once; after the fix the same situation
 * is absorbed by the queue and reported as `wouldHaveOverlappedWithoutFence=true`, which is the
 * field evidence that the fence is doing real work rather than that the race disappeared.
 *
 * getStats/audioLevel is observed but never queued — it runs on observer threads and would
 * deadlock against a transaction owned by an edge executor.
 */
internal object NativeSignalingOverlapObserver {

    internal data class InFlightSrd(
        val edgeKey: String,
        val pcHash: Int,
        val threadName: String,
        val startedAtNs: Long,
    )

    private val inFlight = ConcurrentHashMap<Int, InFlightSrd>()

    @Volatile
    internal var sink: (String) -> Unit = { TalkbackLog.i(it) }

    fun beginSrd(edgeKey: String, pcHash: Int) {
        inFlight[pcHash] = InFlightSrd(
            edgeKey = edgeKey,
            pcHash = pcHash,
            threadName = Thread.currentThread().name,
            startedAtNs = System.nanoTime(),
        )
    }

    fun endSrd(pcHash: Int) {
        inFlight.remove(pcHash)
    }

    /** True when any native SRD call is in flight on any PeerConnection. */
    fun anySrdInFlight(): Boolean = inFlight.isNotEmpty()

    /** Cross-PC read-only probes must skip native work while any SRD is active. */
    fun shouldDeferGetStats(): Boolean = anySrdInFlight()

    /** Any SRD owned by a thread other than the caller — i.e. a real cross-thread native overlap. */
    fun foreignSrdInFlight(): InFlightSrd? {
        val self = Thread.currentThread().name
        return inFlight.values.firstOrNull { it.threadName != self }
    }

    /**
     * Snapshot taken *before* any fence is acquired, so the verdict reflects what would have
     * happened without the transaction queue.
     */
    fun observeRequest(
        op: SignalingOp,
        edgeKey: String,
        pcHash: Int,
        pcGeneration: Long?,
        transaction: PcSignalingTransaction,
    ): Observation = Observation(
        op = op.name,
        edgeKey = edgeKey,
        pcHash = pcHash,
        pcGeneration = pcGeneration,
        threadName = Thread.currentThread().name,
        foreignSrd = foreignSrdInFlight(),
        queuedBehindTransaction = transaction.wouldQueue(),
        transactionOwnerAtRequest = transaction.transactionOwner(),
        requestedAtNs = System.nanoTime(),
    )

    internal class Observation(
        private val op: String,
        private val edgeKey: String,
        private val pcHash: Int,
        private val pcGeneration: Long?,
        private val threadName: String,
        private val foreignSrd: InFlightSrd?,
        private val queuedBehindTransaction: Boolean,
        private val transactionOwnerAtRequest: String?,
        private val requestedAtNs: Long,
    ) {
        /** Emitted after the fence granted admission; silent when nothing raced. */
        fun admitted() {
            if (foreignSrd == null && !queuedBehindTransaction) return
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedAtNs)
            sink(
                "NATIVE_OP_DURING_SRD op=$op thread=$threadName edgeKey=$edgeKey " +
                    "pcHash=$pcHash pcGeneration=${pcGeneration ?: "NONE"} " +
                    "srdInFlight=${foreignSrd?.edgeKey ?: "NONE"} " +
                    "srdInFlightThread=${foreignSrd?.threadName ?: "NONE"} " +
                    "transactionOwner=${transactionOwnerAtRequest ?: "NONE"} " +
                    "queuedBehindTransaction=$queuedBehindTransaction " +
                    "fenceWaitMs=$waitedMs " +
                    "wouldHaveOverlappedWithoutFence=${foreignSrd != null}"
            )
        }

        fun rejected(admission: PcSignalingTransaction.Admission) {
            sink(
                "NATIVE_OP_REJECTED op=$op thread=$threadName edgeKey=$edgeKey " +
                    "pcHash=$pcHash pcGeneration=${pcGeneration ?: "NONE"} " +
                    "admission=${admission.name} " +
                    "srdInFlight=${foreignSrd?.edgeKey ?: "NONE"}"
            )
        }
    }

    /** getStats path: defer native call while any SRD is in flight; log overlap for field evidence. */
    fun logGetStatsDeferred(edgeKey: String, pcHash: Int, pcGeneration: Long?) {
        val foreign = foreignSrdInFlight() ?: inFlight.values.firstOrNull() ?: return
        sink(
            "GET_STATS_DEFERRED_DURING_SRD op=GET_STATS thread=${Thread.currentThread().name} " +
                "edgeKey=$edgeKey pcHash=$pcHash pcGeneration=${pcGeneration ?: "NONE"} " +
                "srdInFlight=${foreign.edgeKey} srdInFlightThread=${foreign.threadName} " +
                "queuedBehindTransaction=false wouldHaveOverlappedWithoutFence=true"
        )
    }

    /** Legacy probe: emitted only when getStats was not deferred (pre-Step-2c). */
    fun observeGetStats(edgeKey: String, pcHash: Int, pcGeneration: Long?) {
        val foreign = foreignSrdInFlight() ?: return
        sink(
            "GET_STATS_DURING_SRD op=GET_STATS thread=${Thread.currentThread().name} " +
                "edgeKey=$edgeKey pcHash=$pcHash pcGeneration=${pcGeneration ?: "NONE"} " +
                "srdInFlight=${foreign.edgeKey} srdInFlightThread=${foreign.threadName} " +
                "queuedBehindTransaction=false wouldHaveOverlappedWithoutFence=true"
        )
    }

    internal fun reset() {
        inFlight.clear()
    }
}
