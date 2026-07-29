package com.talkback.core.signaling.prr

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.RemoteEndpointInfo

/** Endpoint payload for PRR re-announce (HELLO + endpoint information). */
data class LocalEndpointSnapshot(
    val localModuleId: String,
    val endpoints: List<RemoteEndpointInfo> = emptyList(),
    val fromAddress: EndpointAddress,
    val signalingPort: Int = 0
)
