package com.talkback.core.conference.transport

/**
 * Explicit underlay binding for Conference multicast transport.
 *
 * Phase 1 constraint: MUST specify [networkInterfaceName]; MUST NOT rely on default route.
 */
data class ConferenceMulticastNetworkBinding(
    val networkInterfaceName: String,
    /** Optional Android operational network id (e.g. from [SelectedOperationalNetworkRegistry]). */
    val boundNetworkId: String? = null,
) {
    init {
        require(networkInterfaceName.isNotBlank()) { "networkInterfaceName required" }
    }

    companion object {
        fun fromInterfaceName(name: String): ConferenceMulticastNetworkBinding =
            ConferenceMulticastNetworkBinding(networkInterfaceName = name)
    }
}
