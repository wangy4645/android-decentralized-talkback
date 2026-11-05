package com.talkback.core.endpointattachment

import com.talkback.core.model.EndpointAddress

data class EndpointAttachmentEvent(
    val messageId: String,
    val attachmentId: String,
    val from: EndpointAddress,
    val to: EndpointAddress,
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String,
    val bytes: ByteArray,
    val caption: String?,
    val sha256: String
)

data class ChannelAttachmentEvent(
    val messageId: String,
    val attachmentId: String,
    val channelId: String,
    val from: EndpointAddress,
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String,
    val bytes: ByteArray,
    val caption: String?,
    val sha256: String
)
