package com.talkback.core.conference.session.integration

import java.util.concurrent.ConcurrentHashMap

data class Profile01HeadObligationId(
    val conferenceIdHex: String,
    val sessionId: String,
    val headDigest: String,
    val peerModuleId: String,
)

enum class Profile01HeadObligationState {
    OPEN,
    DELIVERING,
    ACKED,
    SUPERSEDED,
    CLOSED,
    FAULT,
}

data class Profile01HeadObligationRecord(
    val id: Profile01HeadObligationId,
    val snapshot: Profile01AuthoritativeHeadSnapshot,
    val state: Profile01HeadObligationState,
    val openedAtMs: Long,
    val attemptedAtMs: Long? = null,
    val attemptCount: Int = 0,
    val sendApiResult: Boolean? = null,
    val lastFailure: String? = null,
    val ackedAtMs: Long? = null,
    val faultAtMs: Long? = null,
    val recoveredFromFault: Boolean = false,
)

class Profile01HeadSupplementObligationLedger {
    private val lock = Any()
    private val bySession =
        ConcurrentHashMap<String, MutableMap<Pair<String, String>, Profile01HeadObligationRecord>>()

    fun openForSnapshot(
        snapshot: Profile01AuthoritativeHeadSnapshot,
        peerModuleIds: Set<String>,
        localModuleId: String,
        nowMs: Long,
    ): List<Profile01HeadObligationRecord> {
        val opened = mutableListOf<Profile01HeadObligationRecord>()
        synchronized(lock) {
            val table = bySession.getOrPut(snapshot.sessionId) { ConcurrentHashMap() }
            peerModuleIds.filter { it != localModuleId }.sorted().forEach { peer ->
                val key = peer to snapshot.headDigest
                table.entries
                    .filter { it.key.first == peer && it.key.second != snapshot.headDigest }
                    .forEach { entry ->
                        val prior = entry.value
                        if (prior.state != Profile01HeadObligationState.CLOSED &&
                            prior.state != Profile01HeadObligationState.SUPERSEDED
                        ) {
                            table[entry.key] =
                                prior.copy(state = Profile01HeadObligationState.SUPERSEDED)
                        }
                    }
                val existing = table[key]
                if (existing == null ||
                    existing.state == Profile01HeadObligationState.SUPERSEDED ||
                    existing.state == Profile01HeadObligationState.CLOSED
                ) {
                    val record =
                        Profile01HeadObligationRecord(
                            id =
                                Profile01HeadObligationId(
                                    conferenceIdHex = snapshot.conferenceIdHex,
                                    sessionId = snapshot.sessionId,
                                    headDigest = snapshot.headDigest,
                                    peerModuleId = peer,
                                ),
                            snapshot = snapshot,
                            state = Profile01HeadObligationState.OPEN,
                            openedAtMs = nowMs,
                        )
                    table[key] = record
                    opened.add(record)
                }
            }
        }
        return opened
    }

    fun recordAttempt(
        id: Profile01HeadObligationId,
        nowMs: Long,
        sendApiResult: Boolean,
        failure: String?,
        faultAfterMs: Long,
    ): Profile01HeadObligationRecord? {
        synchronized(lock) {
            val table = bySession[id.sessionId] ?: return null
            val key = id.peerModuleId to id.headDigest
            val current = table[key] ?: return null
            if (current.state == Profile01HeadObligationState.ACKED ||
                current.state == Profile01HeadObligationState.CLOSED ||
                current.state == Profile01HeadObligationState.SUPERSEDED
            ) {
                return current
            }
            val elapsed = nowMs - current.openedAtMs
            val nextState =
                when {
                    current.state == Profile01HeadObligationState.FAULT ->
                        Profile01HeadObligationState.FAULT
                    elapsed >= faultAfterMs -> Profile01HeadObligationState.FAULT
                    else -> Profile01HeadObligationState.DELIVERING
                }
            val updated =
                current.copy(
                    state = nextState,
                    attemptedAtMs = nowMs,
                    attemptCount = current.attemptCount + 1,
                    sendApiResult = sendApiResult,
                    lastFailure = failure,
                    faultAtMs =
                        if (nextState == Profile01HeadObligationState.FAULT) {
                            current.faultAtMs ?: nowMs
                        } else {
                            current.faultAtMs
                        },
                )
            table[key] = updated
            return updated
        }
    }

    fun onReadyAck(
        sessionId: String,
        peerModuleId: String,
        headDigest: String,
        nowMs: Long,
    ): Profile01HeadObligationRecord? {
        synchronized(lock) {
            val table = bySession[sessionId] ?: return null
            val key = peerModuleId to headDigest
            val current = table[key] ?: return null
            return when (current.state) {
                Profile01HeadObligationState.CLOSED,
                Profile01HeadObligationState.SUPERSEDED,
                -> current
                Profile01HeadObligationState.ACKED -> current
                Profile01HeadObligationState.OPEN,
                Profile01HeadObligationState.DELIVERING,
                Profile01HeadObligationState.FAULT,
                -> {
                    val updated =
                        current.copy(
                            state = Profile01HeadObligationState.ACKED,
                            ackedAtMs = nowMs,
                            recoveredFromFault = current.state == Profile01HeadObligationState.FAULT,
                        )
                    table[key] = updated
                    updated
                }
            }
        }
    }

    fun closePeer(sessionId: String, peerModuleId: String): Int {
        synchronized(lock) {
            val table = bySession[sessionId] ?: return 0
            var n = 0
            table.entries.filter { it.key.first == peerModuleId }.forEach { entry ->
                if (entry.value.state != Profile01HeadObligationState.CLOSED) {
                    table[entry.key] = entry.value.copy(state = Profile01HeadObligationState.CLOSED)
                    n++
                }
            }
            return n
        }
    }

    fun closeAbsentPeers(sessionId: String, remainingPeerIds: Set<String>): Int {
        synchronized(lock) {
            val table = bySession[sessionId] ?: return 0
            var n = 0
            table.entries.filter { it.key.first !in remainingPeerIds }.forEach { entry ->
                if (entry.value.state != Profile01HeadObligationState.CLOSED) {
                    table[entry.key] = entry.value.copy(state = Profile01HeadObligationState.CLOSED)
                    n++
                }
            }
            return n
        }
    }

    fun closeSession(sessionId: String) {
        synchronized(lock) {
            val table = bySession.remove(sessionId) ?: return
            table.replaceAll { _, record ->
                if (record.state == Profile01HeadObligationState.CLOSED) {
                    record
                } else {
                    record.copy(state = Profile01HeadObligationState.CLOSED)
                }
            }
        }
    }

    fun record(id: Profile01HeadObligationId): Profile01HeadObligationRecord? {
        val table = bySession[id.sessionId] ?: return null
        return table[id.peerModuleId to id.headDigest]
    }

    fun currentForPeer(sessionId: String, peerModuleId: String): Profile01HeadObligationRecord? {
        val table = bySession[sessionId] ?: return null
        return table.entries
            .filter { it.key.first == peerModuleId }
            .map { it.value }
            .filter {
                it.state != Profile01HeadObligationState.SUPERSEDED &&
                    it.state != Profile01HeadObligationState.CLOSED
            }
            .maxByOrNull { it.snapshot.membershipVersion }
    }

    fun unfinished(sessionId: String): List<Profile01HeadObligationRecord> {
        val table = bySession[sessionId] ?: return emptyList()
        return table.values.filter {
            it.state == Profile01HeadObligationState.OPEN ||
                it.state == Profile01HeadObligationState.DELIVERING ||
                it.state == Profile01HeadObligationState.FAULT
        }
    }
}
