package com.talkback.appprod.endpointtext

import com.talkback.core.endpointattachment.AttachmentKind

data class MessageAttachmentInfo(
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String,
    val localPath: String,
    val sizeBytes: Long
)
