package com.talkback.core.session

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** P0.1g-2 / IA-002-1: edge workload labels for executor observation and admission. */
enum class EdgeMediaTaskType {
    SRD_APPLY,
    PLAYBACK_CONTROL,
    ICE_CONTROL,
    AUDIO_LEVEL_REFRESH,
    OTHER
}

/**
 * Per-edge serial media execution. SDP/ICE for one peer must not share
 * ConferenceCoordinator's thread or a single global WebRTC queue.
 *
 * IA-002-3: OBSERVATION workloads use a dedicated per-edge lane so a blocked
 * getStats cannot deny MEDIA_CRITICAL admission on the primary lane.
 */
class PeerMediaExecutors(
    private val threadNamePrefix: String = "tb-edge",
    private val logSink: (String) -> Unit = defaultLogSink
) {
    private val primaryExecutors = ConcurrentHashMap<String, ExecutorService>()
    private val observationExecutors = ConcurrentHashMap<String, ExecutorService>()
    private val submitSequenceByEdge = ConcurrentHashMap<String, AtomicLong>()
    private val startSequenceByEdge = ConcurrentHashMap<String, AtomicLong>()
    private val activePrimaryTaskByEdge = ConcurrentHashMap<String, ActivePrimaryTask>()

    fun execute(edgeKey: String, block: () -> Unit) {
        execute(edgeKey, EdgeMediaTaskType.OTHER, origin = EdgeMediaTaskType.OTHER.name, block)
    }

    fun execute(
        edgeKey: String,
        taskType: EdgeMediaTaskType,
        block: () -> Unit
    ) {
        execute(edgeKey, taskType, origin = taskType.name, block = block)
    }

    fun execute(
        edgeKey: String,
        taskType: EdgeMediaTaskType,
        origin: String,
        block: () -> Unit
    ) {
        val category = taskType.contractCategory()
        val executor = laneExecutor(edgeKey, category)
        val submitSeq = submitSequenceByEdge
            .computeIfAbsent(edgeKey) { AtomicLong(0) }
            .incrementAndGet()
        val (sessionId, edge) = parseEdgeKey(edgeKey)
        val submitTimestampMs = System.currentTimeMillis()
        val enqueuedNs = System.nanoTime()
        if (category == EdgeMediaTaskCategory.MEDIA_CRITICAL) {
            activePrimaryTaskByEdge[edgeKey]?.let { blockedBy ->
                logSink(
                    "EDGE_TASK_BLOCKED session=$sessionId edge=$edge taskId=$submitSeq " +
                        "edgeKey=$edgeKey category=$category origin=$origin " +
                        "blockedByTask=${blockedBy.taskId} " +
                        "blockedByCategory=${blockedBy.category} " +
                        "blockedByOrigin=${blockedBy.origin} submitTimestamp=$submitTimestampMs"
                )
            }
        }
        logSink(
            "EDGE_TASK_SUBMIT session=$sessionId edge=$edge taskId=$submitSeq " +
                "edgeKey=$edgeKey taskType=$taskType category=$category origin=$origin " +
                "submitTimestamp=$submitTimestampMs submitSeq=$submitSeq"
        )
        try {
            executor.execute {
                val queueWaitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueuedNs)
                val startSeq = startSequenceByEdge
                    .computeIfAbsent(edgeKey) { AtomicLong(0) }
                    .incrementAndGet()
                val startTimestampMs = System.currentTimeMillis()
                val thread = Thread.currentThread()
                val activePrimary = if (category != EdgeMediaTaskCategory.OBSERVATION) {
                    ActivePrimaryTask(
                        taskId = submitSeq,
                        category = category,
                        origin = origin
                    ).also { activePrimaryTaskByEdge[edgeKey] = it }
                } else {
                    null
                }
                logSink(
                    "EDGE_TASK_START session=$sessionId edge=$edge taskId=$submitSeq " +
                        "edgeKey=$edgeKey taskType=$taskType category=$category origin=$origin " +
                        "thread=${thread.name} queueWaitMs=$queueWaitMs occupancyMs=0 " +
                        "submitTimestamp=$submitTimestampMs startTimestamp=$startTimestampMs " +
                        "submitSeq=$submitSeq startSeq=$startSeq"
                )
                val startedNs = System.nanoTime()
                var success = true
                try {
                    block()
                } catch (_: Throwable) {
                    success = false
                } finally {
                    val occupancyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
                    val endTimestampMs = System.currentTimeMillis()
                    if (activePrimary != null) {
                        activePrimaryTaskByEdge.remove(edgeKey, activePrimary)
                    }
                    logSink(
                        "EDGE_TASK_END session=$sessionId edge=$edge taskId=$submitSeq " +
                            "edgeKey=$edgeKey taskType=$taskType category=$category origin=$origin " +
                            "occupancyMs=$occupancyMs success=$success " +
                            "submitTimestamp=$submitTimestampMs startTimestamp=$startTimestampMs " +
                            "endTimestamp=$endTimestampMs submitSeq=$submitSeq startSeq=$startSeq"
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            Unit
        }
    }

    fun shutdownAll() {
        primaryExecutors.values.forEach { runCatching { it.shutdownNow() } }
        observationExecutors.values.forEach { runCatching { it.shutdownNow() } }
        primaryExecutors.clear()
        observationExecutors.clear()
        submitSequenceByEdge.clear()
        startSequenceByEdge.clear()
        activePrimaryTaskByEdge.clear()
    }

    private fun laneExecutor(edgeKey: String, category: EdgeMediaTaskCategory): ExecutorService {
        return if (category == EdgeMediaTaskCategory.OBSERVATION) {
            observationExecutors.computeIfAbsent(edgeKey) { key ->
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "$threadNamePrefix-obs-$key").apply { isDaemon = true }
                }
            }
        } else {
            primaryExecutors.computeIfAbsent(edgeKey) { key ->
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "$threadNamePrefix-$key").apply { isDaemon = true }
                }
            }
        }
    }

    private fun parseEdgeKey(edgeKey: String): Pair<String, String> {
        val sep = edgeKey.lastIndexOf('|')
        return if (sep <= 0) {
            edgeKey to edgeKey
        } else {
            edgeKey.substring(0, sep) to edgeKey.substring(sep + 1)
        }
    }

    private data class ActivePrimaryTask(
        val taskId: Long,
        val category: EdgeMediaTaskCategory,
        val origin: String
    )

    companion object {
        private val defaultLogSink: (String) -> Unit = { message ->
            try {
                Log.i("Talkback", message)
            } catch (_: Throwable) {
                // JVM unit tests without Robolectric.
            }
        }
    }
}
