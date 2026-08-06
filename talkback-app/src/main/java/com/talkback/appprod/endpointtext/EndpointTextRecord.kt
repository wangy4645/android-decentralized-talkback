package com.talkback.appprod.endpointtext

/**
 * One row in the process-local EndpointText recent presentation cache.
 * Not transport state — clearing has zero protocol impact.
 */
data class EndpointTextRecord(
    val endpointKey: String,
    val text: String,
    val timestampMs: Long,
    val direction: EndpointTextDirection
)

enum class EndpointTextDirection {
    INBOUND,
    OUTBOUND
}
