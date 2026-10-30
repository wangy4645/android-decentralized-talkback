package com.talkback.core.conference.capacity

/**
 * Profile 02 A1 unicast leg identity (G-RES-3 harness / future production seam).
 * Each leg MUST be a distinct receiver identity + bindable UDP endpoint.
 */
data class PerReceiverUnicastEndpoint(
    val receiverModuleId: String,
    val moduleFixedIp: String,
    val mediaPort: Int,
) {
    fun endpointKey(): String = "$receiverModuleId@$moduleFixedIp:$mediaPort"
}
