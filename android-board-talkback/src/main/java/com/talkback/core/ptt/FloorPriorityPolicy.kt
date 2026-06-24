package com.talkback.core.ptt

import com.talkback.core.model.EndpointPriority

object FloorPriorityPolicy {
    fun effectivePriority(
        registered: EndpointPriority?,
        claimed: EndpointPriority
    ): EndpointPriority = registered ?: claimed

    fun canPreempt(
        challenger: EndpointPriority,
        holder: EndpointPriority,
        preemptThreshold: EndpointPriority = EndpointPriority.EMERGENCY
    ): Boolean = challenger.weight > holder.weight && challenger.weight >= preemptThreshold.weight
}
