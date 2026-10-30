package com.talkback.core.session.gbc.issuance

import com.talkback.core.session.gbc.crypto.GenerationFactInclusionProofVerifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Crash-injection seam for PV2-G13 harness (tests only).
 */
enum class IssuanceStoreCrashPoint {
    DURING_FINALIZE_PERSIST,
}

fun interface IssuanceStoreCrashHook {
    fun maybeCrash(point: IssuanceStoreCrashPoint)
}

/**
 * Origin-local durable issuance store (PV2-B).
 * FINALIZE is a single durable transition binding commitment + exact signedFactBytes (PV2-IA-T1).
 */
interface GenerationFactIssuanceStore {
    fun retirementPhase(key: GenerationFactIssuanceKey): IssuanceRetirementPhase

    fun prepare(
        key: GenerationFactIssuanceKey,
        record: PreparedIssuanceRecord,
    ): PrepareIssuanceResult

    fun abortPrepare(
        key: GenerationFactIssuanceKey,
        prepareId: String,
    ): Boolean

    fun finalize(
        key: GenerationFactIssuanceKey,
        prepareId: String,
        signedFactBytes: ByteArray,
    ): FinalizeIssuanceResult

    fun findFinalized(
        key: GenerationFactIssuanceKey,
        factCommitment: ByteArray,
    ): FinalizedIssuanceRecord?

    fun finalizedRecords(key: GenerationFactIssuanceKey): List<FinalizedIssuanceRecord>

    fun merkleRoot(key: GenerationFactIssuanceKey): ByteArray?

    fun beginRetirementPrepare(key: GenerationFactIssuanceKey): RetirementPrepareResult

    fun proposeTerminalHead(key: GenerationFactIssuanceKey): TerminalHeadResult

    fun abortRetirement(key: GenerationFactIssuanceKey): Boolean

    fun proposedTerminalHead(key: GenerationFactIssuanceKey): ProposedTerminalHead?
}

data class KeyIssuanceState(
    var retirementPhase: IssuanceRetirementPhase = IssuanceRetirementPhase.ACTIVE,
    var fenceClosed: Boolean = false,
    val prepared: LinkedHashMap<String, PreparedIssuanceRecord> = linkedMapOf(),
    val finalizedByCommitment: LinkedHashMap<String, FinalizedIssuanceRecord> = linkedMapOf(),
    val finalizedOrder: MutableList<ByteArray> = mutableListOf(),
    var proposedTerminalHead: ProposedTerminalHead? = null,
    var proposedHeadGeneration: Long = 0,
)

/**
 * Thread-safe in-memory store with optional snapshot persistence and crash hooks.
 */
class DurableGenerationFactIssuanceStore(
    private val persistence: IssuanceStatePersistence? = null,
    private val crashHook: IssuanceStoreCrashHook? = null,
) : GenerationFactIssuanceStore {
    private val states = ConcurrentHashMap<String, KeyIssuanceState>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    override fun retirementPhase(key: GenerationFactIssuanceKey): IssuanceRetirementPhase =
        lock(key).withLock {
            state(key).retirementPhase
        }

    override fun prepare(
        key: GenerationFactIssuanceKey,
        record: PreparedIssuanceRecord,
    ): PrepareIssuanceResult =
        lock(key).withLock {
            val current = state(key)
            if (current.retirementPhase == IssuanceRetirementPhase.TERMINAL_HEAD_PROPOSED) {
                return PrepareIssuanceResult.Retired
            }
            val admittedBeforeFence = !current.fenceClosed
            val stored =
                record.copy(
                    admittedBeforeFence = admittedBeforeFence,
                )
            val next = current.copy()
            next.prepared[stored.prepareId] = stored
            commitState(key, next)
            PrepareIssuanceResult.Prepared(stored.prepareId, stored.factCommitment.copyOf())
        }

    override fun abortPrepare(
        key: GenerationFactIssuanceKey,
        prepareId: String,
    ): Boolean =
        lock(key).withLock {
            val current = state(key)
            if (!current.prepared.containsKey(prepareId)) return false
            val next = current.copy()
            next.prepared.remove(prepareId)
            commitState(key, next)
            true
        }

    override fun finalize(
        key: GenerationFactIssuanceKey,
        prepareId: String,
        signedFactBytes: ByteArray,
    ): FinalizeIssuanceResult =
        lock(key).withLock {
            val current = state(key)
            if (current.retirementPhase == IssuanceRetirementPhase.TERMINAL_HEAD_PROPOSED) {
                return FinalizeIssuanceResult.Retired
            }
            val prepared = current.prepared[prepareId] ?: return FinalizeIssuanceResult.PrepareNotFound
            if (current.fenceClosed && !prepared.admittedBeforeFence) {
                return FinalizeIssuanceResult.FenceRejected
            }
            val commitmentKey = prepared.factCommitment.toHexKey()
            val existing = current.finalizedByCommitment[commitmentKey]
            if (existing != null) {
                return if (existing.signedFactBytes.contentEquals(signedFactBytes)) {
                    FinalizeIssuanceResult.Finalized(existing, idempotentReplay = true)
                } else {
                    FinalizeIssuanceResult.IntegrityConflict(
                        "factCommitment already finalized with different signedFactBytes",
                    )
                }
            }
            val record =
                FinalizedIssuanceRecord(
                    factCommitment = prepared.factCommitment.copyOf(),
                    signedFactBytes = signedFactBytes.copyOf(),
                    merkleIndex = current.finalizedOrder.size,
                )
            val next = current.copy()
            next.finalizedByCommitment[commitmentKey] = record
            next.finalizedOrder += record.factCommitment.copyOf()
            next.prepared.remove(prepareId)
            commitState(key, next, IssuanceStoreCrashPoint.DURING_FINALIZE_PERSIST)
            FinalizeIssuanceResult.Finalized(record, idempotentReplay = false)
        }

    override fun findFinalized(
        key: GenerationFactIssuanceKey,
        factCommitment: ByteArray,
    ): FinalizedIssuanceRecord? =
        lock(key).withLock {
            state(key).finalizedByCommitment[factCommitment.toHexKey()]
        }

    override fun finalizedRecords(key: GenerationFactIssuanceKey): List<FinalizedIssuanceRecord> =
        lock(key).withLock {
            state(key).finalizedOrder.mapNotNull { commitment ->
                state(key).finalizedByCommitment[commitment.toHexKey()]
            }
        }

    override fun merkleRoot(key: GenerationFactIssuanceKey): ByteArray? =
        lock(key).withLock {
            val order = state(key).finalizedOrder
            if (order.isEmpty()) null else GenerationFactInclusionProofVerifier.merkleRoot(order)
        }

    override fun beginRetirementPrepare(key: GenerationFactIssuanceKey): RetirementPrepareResult =
        lock(key).withLock {
            val current = state(key)
            when (current.retirementPhase) {
                IssuanceRetirementPhase.TERMINAL_HEAD_PROPOSED ->
                    return RetirementPrepareResult.AlreadyProposed
                IssuanceRetirementPhase.RETIREMENT_PREPARE ->
                    return RetirementPrepareResult.AlreadyPrepared
                IssuanceRetirementPhase.ACTIVE -> Unit
            }
            val next = current.copy()
            next.fenceClosed = true
            next.retirementPhase = IssuanceRetirementPhase.RETIREMENT_PREPARE
            commitState(key, next)
            RetirementPrepareResult.Started
        }

    override fun proposeTerminalHead(key: GenerationFactIssuanceKey): TerminalHeadResult =
        lock(key).withLock {
            val current = state(key)
            if (current.retirementPhase != IssuanceRetirementPhase.RETIREMENT_PREPARE) {
                return TerminalHeadResult.NotPrepared
            }
            val next = current.copy()
            next.prepared.clear()
            val root =
                if (next.finalizedOrder.isEmpty()) {
                    ByteArray(32)
                } else {
                    GenerationFactInclusionProofVerifier.merkleRoot(next.finalizedOrder)
                }
            val head =
                ProposedTerminalHead(
                    moduleId = key.moduleId,
                    signerKeyVersion = key.signerKeyVersion,
                    issuanceRoot = root,
                    finalizedCount = next.finalizedOrder.size,
                )
            next.proposedHeadGeneration += 1
            next.proposedTerminalHead = head
            next.retirementPhase = IssuanceRetirementPhase.TERMINAL_HEAD_PROPOSED
            commitState(key, next)
            TerminalHeadResult.Proposed(head)
        }

    override fun abortRetirement(key: GenerationFactIssuanceKey): Boolean =
        lock(key).withLock {
            val current = state(key)
            if (current.retirementPhase == IssuanceRetirementPhase.ACTIVE) return false
            val next = current.copy()
            next.retirementPhase = IssuanceRetirementPhase.ACTIVE
            next.fenceClosed = false
            next.proposedTerminalHead = null
            next.proposedHeadGeneration += 1
            commitState(key, next)
            true
        }

    override fun proposedTerminalHead(key: GenerationFactIssuanceKey): ProposedTerminalHead? =
        lock(key).withLock {
            state(key).proposedTerminalHead
        }

    fun reloadFromPersistence(key: GenerationFactIssuanceKey) {
        val snapshot = persistence?.load(key) ?: return
        lock(key).withLock {
            states[key.storageId()] = snapshot
        }
    }

    fun preparedRecords(key: GenerationFactIssuanceKey): Map<String, PreparedIssuanceRecord> =
        lock(key).withLock {
            state(key).prepared.toMap()
        }

    private fun state(key: GenerationFactIssuanceKey): KeyIssuanceState =
        states.getOrPut(key.storageId()) {
            persistence?.load(key) ?: KeyIssuanceState()
        }

    private fun commitState(
        key: GenerationFactIssuanceKey,
        next: KeyIssuanceState,
        crashPoint: IssuanceStoreCrashPoint? = null,
    ) {
        if (persistence != null) {
            crashPoint?.let { crashHook?.maybeCrash(it) }
            persistence.save(key, next)
        }
        states[key.storageId()] = next
    }

    private fun lock(key: GenerationFactIssuanceKey): ReentrantLock =
        locks.getOrPut(key.storageId()) { ReentrantLock() }
}

private fun KeyIssuanceState.copy(): KeyIssuanceState =
    KeyIssuanceState(
        retirementPhase = retirementPhase,
        fenceClosed = fenceClosed,
        prepared = LinkedHashMap(prepared),
        finalizedByCommitment = LinkedHashMap(finalizedByCommitment),
        finalizedOrder = finalizedOrder.map { it.copyOf() }.toMutableList(),
        proposedTerminalHead = proposedTerminalHead,
        proposedHeadGeneration = proposedHeadGeneration,
    )

interface IssuanceStatePersistence {
    fun save(
        key: GenerationFactIssuanceKey,
        state: KeyIssuanceState,
    )

    fun load(key: GenerationFactIssuanceKey): KeyIssuanceState?
}

internal fun ByteArray.toHexKey(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
