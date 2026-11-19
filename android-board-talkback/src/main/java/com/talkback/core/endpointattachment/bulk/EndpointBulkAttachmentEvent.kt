package com.talkback.core.endpointattachment.bulk

import com.talkback.core.endpointattachment.AttachmentKind
import com.talkback.core.model.EndpointAddress
import java.io.File

data class EndpointBulkAttachmentEvent(
    val messageId: String,
    val attachmentId: String,
    val from: EndpointAddress,
    val to: EndpointAddress,
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String,
    val localFile: File,
    val caption: String?,
    val sha256: String,
)