package com.talkback.appprod.endpointtext

/**
 * One row in the EndpointText presentation / persistence store.
 */
data class EndpointTextRecord(
    val endpointKey: String,
    val text: String,
    val timestampMs: Long,
    val direction: EndpointTextDirection,
    val attachment: MessageAttachmentInfo? = null
)

enum class EndpointTextDirection {
    INBOUND,
    OUTBOUND
}
