package com.talkback.core.endpointattachment

/** Binds an in-flight attachment to a single sender EndpointKey (not sessionId). */
internal data class AttachmentTransferKey(
    val attachmentId: String,
    val senderEndpointKey: String
)
