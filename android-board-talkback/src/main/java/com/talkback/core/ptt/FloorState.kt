package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointPriority

data class FloorSnapshot(
    val owner: EndpointAddress?,
    val epoch: Long,
    val version: Long
)

enum class FloorGrantResult {
    GRANTED,
    DENIED,
    STALE_VERSION,
    PREEMPTED
}

/**
 * Explicit floor ownership with versioned CAS (no wall-clock arbitration).
 */
class FloorState(
    private val preemptThreshold: EndpointPriority = EndpointPriority.EMERGENCY
) {
    private var owner: EndpointAddress? = null
    private var ownerPriority: EndpointPriority = EndpointPriority.NORMAL
    private var epoch: Long = 0
    private var version: Long = 0

    @Synchronized
    fun snapshot(): FloorSnapshot = FloorSnapshot(owner, epoch, version)

    @Synchronized
    fun owner(): EndpointAddress? = owner

    @Synchronized
    fun ownerPriority(): EndpointPriority = ownerPriority

    @Synchronized
    fun version(): Long = version

    @Synchronized
    fun epoch(): Long = epoch

    /**
     * Attempt to grant floor to [requester] when incoming [requestVersion] wins CAS.
     */
    @Synchronized
    fun tryGrant(
        requester: EndpointAddress,
        requestVersion: Long,
        requestEpoch: Long,
        priority: EndpointPriority,
        requestTsMs: Long,
        arbitrator: FloorArbitrator
    ): FloorGrantResult {
        if (requestEpoch < epoch) return FloorGrantResult.STALE_VERSION
        val currentOwner = owner
        if (currentOwner != null && currentOwner != requester) {
            if (FloorPriorityPolicy.canPreempt(priority, ownerPriority, preemptThreshold)) {
                owner = requester
                ownerPriority = priority
                version = maxOf(version + 1, requestVersion)
                epoch += 1
                return FloorGrantResult.PREEMPTED
            }
            return FloorGrantResult.DENIED
        }
        owner = requester
        ownerPriority = priority
        version = maxOf(version + 1, requestVersion)
        return FloorGrantResult.GRANTED
    }

    @Synchronized
    fun release(by: EndpointAddress): Boolean {
        if (owner != by) return false
        owner = null
        ownerPriority = EndpointPriority.NORMAL
        version += 1
        epoch += 1
        return true
    }

    @Synchronized
    fun applyGrant(
        owner: EndpointAddress,
        version: Long,
        epoch: Long,
        priority: EndpointPriority = EndpointPriority.NORMAL
    ) {
        if (epoch < this.epoch) return
        this.owner = owner
        this.ownerPriority = priority
        this.version = maxOf(this.version, version)
        if (epoch > this.epoch) this.epoch = epoch
    }

    @Synchronized
    fun reset() {
        owner = null
        ownerPriority = EndpointPriority.NORMAL
        epoch = 0
        version = 0
    }

    @Synchronized
    fun nextRequestVersion(): Long {
        version += 1
        return version
    }
}
