package com.talkback.core.conference.transport

/**
 * Phase 1: MulticastLock is WiFi-specific; non-WiFi mesh netdev MUST NOT hard-depend on it.
 */
enum class MulticastLockPolicy {
    /** Acquire [AndroidWifiMulticastLockSeam] at transport scope begin (WiFi underlay / lab). */
    WIFI_MULTICAST_LOCK,

    /** No MulticastLock — mesh or other non-WiFi netdev transport scope. */
    NONE,
}
