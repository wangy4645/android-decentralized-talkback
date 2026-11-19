package com.talkback.appprod.endpointtext

import com.talkback.core.endpointattachment.AttachmentKind

/** In-flight / failed outbound bulk attachment (not yet persisted as a normal message). */
data class BulkOutboxItem(
    val localId: String,
    val endpointKey: String,
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String,
    val localPath: String,
    val sizeBytes: Long,
    val caption: String?,
    val timestampMs: Long,
    val progressPercent: Int,
    val stage: AttachmentTransferStage = AttachmentTransferStage.PREPARING,
    val failed: Boolean = false,
) {
    fun toRecord(): EndpointTextRecord =
        EndpointTextRecord(
            endpointKey = endpointKey,
            text = caption.orEmpty(),
            timestampMs = timestampMs,
            direction = EndpointTextDirection.OUTBOUND,
            attachment = MessageAttachmentInfo(
                kind = kind,
                mimeType = mimeType,
                fileName = fileName,
                localPath = localPath,
                sizeBytes = sizeBytes,
                transferProgressPercent = if (failed) null else progressPercent.coerceIn(0, 100),
                transferStage = if (failed) null else stage,
                transferFailed = failed,
            ),
        )
}
