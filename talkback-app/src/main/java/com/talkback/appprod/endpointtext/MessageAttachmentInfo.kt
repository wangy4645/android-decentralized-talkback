package com.talkback.appprod.endpointtext

import com.talkback.core.endpointattachment.AttachmentKind

enum class AttachmentTransferStage {
    PREPARING,
    WAITING_PEER,
    SENDING,
}

data class MessageAttachmentInfo(
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String,
    val localPath: String,
    val sizeBytes: Long,
    /** 0..100 while sending; null when idle / finished. */
    val transferProgressPercent: Int? = null,
    val transferStage: AttachmentTransferStage? = null,
    val transferFailed: Boolean = false,
)
