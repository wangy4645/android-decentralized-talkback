package com.talkback.core.signaling

import com.talkback.core.signaling.link.LinkQualificationFactSink
import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.link.LinkQualificationTrace
import com.talkback.core.signaling.link.LinkQualificationTracker
import com.talkback.core.signaling.link.QualificationRepairCoordinator
import com.talkback.core.signaling.link.TransportCapabilitySnapshot
import com.talkback.core.signaling.link.TransportRepairRequester
import com.talkback.core.signaling.prr.PeerReachabilityReannounceController
import com.talkback.core.util.TransportCapabilityTrace
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** C6-B trace + minimal binding repair on network change. Does not mutate recovery state. */
class SignalingTransportManager(
    private val repairCoordinator: QualificationRepairCoordinator = QualificationRepairCoordinator(),
    private var prrController: PeerReachabilityReannounceController? = null
) : SignalingTransportLifecycleReporter {
    private val transportInstanceCounter = AtomicLong(0)
    private val bindings = CopyOnWriteArrayList<SignalingTransportBinding>()
    private val qualificationStateListeners =
        CopyOnWriteArrayList<(LinkQualificationState, LinkQualificationState) -> Unit>()
    private val linkQualificationTracker = LinkQualificationTracker()
    private var qualificationRepairBinding: SignalingTransportBinding? = null
    private var transportInstanceId: Long = 0L
    private var transportState: String = STATE_UNKNOWN
    private var currentNetworkId: String = "none"
    private var currentSocketId: Long = 0L
    private var boundNetworkId: String = BOUND_NETWORK_UNBOUND
    private var socketOpen: Boolean = false
    private var receiveLoopActive: Boolean = false
    private var lastNotifiedTransportEpoch: Long = 0L

    init {
        wireQualificationRepair()
    }

    private fun wireQualificationRepair() {
        repairCoordinator.tracker = linkQualificationTracker
        repairCoordinator.currentNetworkId = { currentNetworkId }
        repairCoordinator.rebindSignaling = { networkId, reason ->
            qualificationRepairBinding?.rebindBinding(networkId, reason)
        }
        linkQualificationTracker.onQualificationRepairHandoff = { socketId, generation ->
            repairCoordinator.onQualificationTimeout(socketId, generation)
        }
        linkQualificationTracker.onQualificationStateChanged = { oldState, newState ->
            repairCoordinator.onQualificationStateChanged(oldState, newState)
            qualificationStateListeners.forEach { it(oldState, newState) }
        }
    }

    fun linkQualificationSnapshot(): TransportCapabilitySnapshot =
        repairCoordinator.enrichSnapshot(linkQualificationTracker.snapshot())

    fun readLinkQualificationSnapshot(caller: String): TransportCapabilitySnapshot {
        val snapshot = linkQualificationSnapshot()
        LinkQualificationTrace.linkQualificationSnapshotRead(caller, snapshot)
        return snapshot
    }

    fun dumpLinkQualificationSnapshot(reason: String): TransportCapabilitySnapshot =
        readLinkQualificationSnapshot("dump:$reason")

    fun linkQualificationFacts(): LinkQualificationFactSink = linkQualificationTracker

    fun qualificationRepairRequester(): TransportRepairRequester = repairCoordinator

    fun onLinkQualificationStateChanged(
        listener: (LinkQualificationState, LinkQualificationState) -> Unit
    ) {
        qualificationStateListeners.add(listener)
    }

    fun attachBinding(binding: SignalingTransportBinding) {
        bindings.addIfAbsent(binding)
    }

    fun attachSignalingBinding(binding: SignalingTransportBinding) {
        qualificationRepairBinding = binding
        attachBinding(binding)
    }

    fun wirePrrController(controller: PeerReachabilityReannounceController) {
        prrController = controller
    }

    fun onNetworkAvailable(networkId: String, interfaceName: String) {
        val oldNetworkId = currentNetworkId
        currentNetworkId = networkId
        TransportCapabilityTrace.setCurrentNetworkId(networkId)
        TransportCapabilityTrace.networkCapabilityAvailable(interfaceName, networkId)
        if (oldNetworkId != networkId) {
            TransportCapabilityTrace.networkChanged(oldNetworkId, networkId, "network_available")
        }
        repairCoordinator.onNetworkAvailable(networkId)
        bindings.forEach { it.rebindBinding(networkId, "network_available") }
        reevaluateTransportState("network_available")
    }

    fun onNetworkLost(networkId: String, interfaceName: String) {
        TransportCapabilityTrace.networkCapabilityLost(interfaceName, networkId)
        linkQualificationTracker.onNetworkLost()
        bindings.forEach { it.invalidateBinding("network_lost") }
        socketOpen = false
        receiveLoopActive = false
        currentSocketId = 0L
        boundNetworkId = BOUND_NETWORK_UNBOUND
        reevaluateTransportState("network_lost")
    }

    override fun onSocketCreated(socketId: Long, port: Int) {
        transportInstanceId = transportInstanceCounter.incrementAndGet()
        currentSocketId = socketId
        socketOpen = true
        TransportCapabilityTrace.socketCreate(socketId, port, boundNetworkId, transportInstanceId)
        TransportCapabilityTrace.socketBind(socketId, port, boundNetworkId, transportInstanceId)
        notifyTransportEpochIfAdvanced("socket_created")
        reevaluateTransportState("socket_created")
    }

    override fun onSocketClosed(socketId: Long, reason: String) {
        TransportCapabilityTrace.socketClose(socketId, reason, transportInstanceId)
        if (currentSocketId == socketId) {
            socketOpen = false
            receiveLoopActive = false
            currentSocketId = 0L
            boundNetworkId = BOUND_NETWORK_UNBOUND
        }
        reevaluateTransportState("socket_closed:$reason")
    }

    override fun onSocketBound(socketId: Long, port: Int, boundNetworkId: String) {
        this.boundNetworkId = boundNetworkId
        TransportCapabilityTrace.socketBind(socketId, port, boundNetworkId, transportInstanceId)
        reevaluateTransportState("socket_bound")
    }

    override fun onSocketRebind(socketId: Long, port: Int, reason: String, boundNetworkId: String) {
        currentSocketId = socketId
        this.boundNetworkId = boundNetworkId
        socketOpen = true
        TransportCapabilityTrace.socketRebind(socketId, port, reason, boundNetworkId, transportInstanceId)
        notifyTransportEpochIfAdvanced(reason)
        reevaluateTransportState("socket_rebind:$reason")
    }

    override fun onReceiveLoopStarted(socketId: Long) {
        receiveLoopActive = currentSocketId == socketId && socketOpen
        TransportCapabilityTrace.receiveLoopStarted(
            socketId = socketId,
            threadId = Thread.currentThread().id,
            networkId = currentNetworkId,
            boundNetworkId = boundNetworkId
        )
        reevaluateTransportState("receive_loop_started")
    }

    override fun onReceiveLoopStopped(socketId: Long) {
        if (currentSocketId == socketId) receiveLoopActive = false
        TransportCapabilityTrace.receiveLoopStopped(socketId, "lifecycle")
        reevaluateTransportState("receive_loop_stopped")
    }

    private fun notifyTransportEpochIfAdvanced(reason: String) {
        val epoch = linkQualificationTracker.snapshot().rebindGeneration
        if (epoch <= lastNotifiedTransportEpoch) return
        lastNotifiedTransportEpoch = epoch
        prrController?.onTransportEpochChanged(
            transportEpoch = epoch,
            socketId = currentSocketId,
            networkId = currentNetworkId,
            reason = reason
        )
    }

    private fun reevaluateTransportState(reason: String) {
        val newState = evaluateTransportState(
            currentNetworkId = currentNetworkId,
            socketOpen = socketOpen,
            currentSocketId = currentSocketId,
            receiveLoopActive = receiveLoopActive,
            boundNetworkId = boundNetworkId
        )
        if (newState != transportState) {
            val old = transportState
            transportState = newState
            TransportCapabilityTrace.transportStateChanged(
                oldState = old,
                newState = newState,
                transportInstanceId = transportInstanceId,
                detail = "reason=$reason networkId=$currentNetworkId socketId=$currentSocketId boundNetworkId=$boundNetworkId"
            )
        }
        if (newState == STATE_TRANSPORT_READY) {
            TransportCapabilityTrace.transportReady(
                networkId = currentNetworkId,
                socketId = currentSocketId,
                boundNetworkId = boundNetworkId,
                transportInstanceId = transportInstanceId
            )
        }
    }

    companion object {
        const val BOUND_NETWORK_UNBOUND = "unbound"
        const val STATE_UNKNOWN = "UNKNOWN"
        const val STATE_BINDING = "BINDING"
        const val STATE_TRANSPORT_READY = "TRANSPORT_READY"

        fun evaluateTransportState(
            currentNetworkId: String,
            socketOpen: Boolean,
            currentSocketId: Long,
            receiveLoopActive: Boolean,
            boundNetworkId: String
        ): String {
            if (currentNetworkId == "none" || !socketOpen || currentSocketId <= 0L) return STATE_UNKNOWN
            if (boundNetworkId == BOUND_NETWORK_UNBOUND || !receiveLoopActive) return STATE_BINDING
            return STATE_TRANSPORT_READY
        }
    }
}
