package com.talkback.core.signaling.link

import com.talkback.core.util.TalkbackLog

/**
 * R28-L.1.2: observe-only link qualification observation contract.
 * Grep: LINK_QUALIFICATION_STATE_CHANGED, LINK_FACT_RECEIVED, LINK_QUALIFICATION_SNAPSHOT_READ
 */
object LinkQualificationTrace {

    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        logSink = sink
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }

    fun linkFactReceived(
        fact: String,
        socketId: Long,
        rebindGeneration: Long,
        networkId: String,
        accepted: Boolean,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_FACT_RECEIVED remote=$remote fact=$fact socketId=$socketId " +
                "rebindGeneration=$rebindGeneration networkId=$networkId accepted=$accepted " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkQualificationStateChanged(
        oldState: LinkQualificationState,
        newState: LinkQualificationState,
        reason: String,
        socketId: Long,
        rebindGeneration: Long,
        networkId: String,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_QUALIFICATION_STATE_CHANGED remote=$remote oldState=$oldState newState=$newState " +
                "socketId=$socketId rebindGeneration=$rebindGeneration networkId=$networkId " +
                "reason=$reason timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkQualificationSnapshotRead(
        caller: String,
        snapshot: TransportCapabilitySnapshot,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_QUALIFICATION_SNAPSHOT_READ remote=$remote caller=$caller " +
                "state=${snapshot.linkQualification} socketId=${snapshot.socketId} " +
                "rebindGeneration=${snapshot.rebindGeneration} networkId=${snapshot.networkId} " +
                "hasOutboundAfterRebind=${snapshot.hasOutboundAfterRebind} " +
                "hasInboundAfterRebind=${snapshot.hasInboundAfterRebind} " +
                "qualificationRetryRequested=${snapshot.qualificationRetryRequested} " +
                "repairAttempt=${snapshot.repairAttempt} transportRepairState=${snapshot.transportRepairState} " +
                "repairStable=${snapshot.repairStable} " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkQualificationRepairRequested(
        reason: QualificationFailureReason,
        oldSocketId: Long,
        newSocketId: Long,
        qualificationGeneration: Long,
        repairAttempt: Int,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_QUALIFICATION_REPAIR_REQUESTED remote=$remote reason=$reason " +
                "oldSocketId=$oldSocketId newSocketId=$newSocketId " +
                "qualificationGeneration=$qualificationGeneration repairAttempt=$repairAttempt " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkQualificationRepairStarted(
        reason: QualificationFailureReason,
        oldSocketId: Long,
        newSocketId: Long,
        qualificationGeneration: Long,
        repairAttempt: Int,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_QUALIFICATION_REPAIR_STARTED remote=$remote reason=$reason " +
                "oldSocketId=$oldSocketId newSocketId=$newSocketId " +
                "qualificationGeneration=$qualificationGeneration repairAttempt=$repairAttempt " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkQualificationRepairSucceeded(
        reason: QualificationFailureReason,
        oldSocketId: Long,
        newSocketId: Long,
        qualificationGeneration: Long,
        repairAttempt: Int,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_QUALIFICATION_REPAIR_SUCCEEDED remote=$remote reason=$reason " +
                "oldSocketId=$oldSocketId newSocketId=$newSocketId " +
                "qualificationGeneration=$qualificationGeneration repairAttempt=$repairAttempt " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkQualificationRepairExhausted(
        reason: QualificationFailureReason,
        oldSocketId: Long,
        newSocketId: Long,
        qualificationGeneration: Long,
        repairAttempt: Int,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_QUALIFICATION_REPAIR_EXHAUSTED remote=$remote reason=$reason " +
                "oldSocketId=$oldSocketId newSocketId=$newSocketId " +
                "qualificationGeneration=$qualificationGeneration repairAttempt=$repairAttempt " +
                "nextRestart=network_changed|socket_error|manual " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkQualificationRepairDuplicateRejected(
        reason: QualificationFailureReason,
        socketId: Long,
        qualificationGeneration: Long,
        repairAttempt: Int,
        repairState: TransportRepairState,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_QUALIFICATION_REPAIR_DUPLICATE_REJECTED remote=$remote reason=$reason " +
                "socketId=$socketId qualificationGeneration=$qualificationGeneration " +
                "repairAttempt=$repairAttempt transportRepairState=$repairState " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkRepairSocketContext(
        phase: String,
        repairReason: QualificationFailureReason,
        repairAttempt: Int,
        beforeSocketId: Long,
        afterSocketId: Long,
        rebindGeneration: Long,
        networkId: String,
        boundNetwork: String = "-",
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_REPAIR_SOCKET_CONTEXT remote=$remote phase=$phase reason=$repairReason " +
                "repairAttempt=$repairAttempt beforeSocketId=$beforeSocketId afterSocketId=$afterSocketId " +
                "rebindGeneration=$rebindGeneration networkId=$networkId boundNetwork=$boundNetwork " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun linkFirstPacketAfterRepair(
        direction: String,
        socketId: Long,
        rebindGeneration: Long,
        networkId: String,
        remote: String = LOCAL_REMOTE
    ) {
        log(
            "LINK_FIRST_${direction}_AFTER_REPAIR remote=$remote socketId=$socketId " +
                "rebindGeneration=$rebindGeneration networkId=$networkId " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun remoteReceiveObserved(
        localModuleId: String,
        remoteModuleId: String,
        socketId: Long,
        rebindGeneration: Long,
        signalType: String,
        srcIp: String,
        srcPort: Int
    ) {
        log(
            "REMOTE_RECEIVE_OBSERVED local=$localModuleId remote=$remoteModuleId " +
                "socketId=$socketId socketEpoch=$rebindGeneration signalType=$signalType " +
                "src=$srcIp:$srcPort timestamp=${System.currentTimeMillis()}"
        )
    }

    private const val LOCAL_REMOTE = "local"
}