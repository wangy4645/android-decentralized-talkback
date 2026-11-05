package com.talkback.core.endpointattachment

import java.util.BitSet

internal class AttachmentReassemblyBuffer(
    val meta: EndpointAttachmentMetaPayload,
    private val onLog: (String) -> Unit
) {
    private val received = BitSet(meta.totalChunks)
    private val chunks = arrayOfNulls<ByteArray>(meta.totalChunks)
    private var receivedBytes = 0

    fun ingest(index: Int, bytes: ByteArray): ByteArray? {
        if (index < 0 || index >= meta.totalChunks) {
            onLog("ATTACHMENT chunk_index_oob attachmentId=${meta.attachmentId} index=$index")
            return null
        }
        if (received.get(index)) {
            onLog("ATTACHMENT chunk_dup attachmentId=${meta.attachmentId} index=$index")
            return null
        }
        received.set(index)
        chunks[index] = bytes
        receivedBytes += bytes.size
        if (received.cardinality() != meta.totalChunks) return null
        val out = ByteArray(meta.totalBytes)
        var offset = 0
        for (i in 0 until meta.totalChunks) {
            val part = chunks[i] ?: return null
            part.copyInto(out, destinationOffset = offset)
            offset += part.size
        }
        if (offset != meta.totalBytes) {
            onLog("ATTACHMENT size_mismatch attachmentId=${meta.attachmentId}")
            return null
        }
        if (meta.sha256.isBlank()) {
            onLog("ATTACHMENT sha256_missing attachmentId=${meta.attachmentId}")
            return null
        }
        val actual = AttachmentChunker.sha256Hex(out)
        if (!actual.equals(meta.sha256, ignoreCase = true)) {
            onLog("ATTACHMENT sha256_fail attachmentId=${meta.attachmentId}")
            return null
        }
        return out
    }
}

internal class ChannelAttachmentReassemblyBuffer(
    val meta: ChannelAttachmentMetaPayload,
    private val onLog: (String) -> Unit
) {
    private val inner = AttachmentReassemblyBuffer(
        EndpointAttachmentMetaPayload(
            messageId = meta.messageId,
            attachmentId = meta.attachmentId,
            kind = meta.kind,
            mimeType = meta.mimeType,
            fileName = meta.fileName,
            totalBytes = meta.totalBytes,
            totalChunks = meta.totalChunks,
            sha256 = meta.sha256,
            caption = meta.caption
        ),
        onLog
    )

    fun ingest(index: Int, bytes: ByteArray): ByteArray? = inner.ingest(index, bytes)
}
