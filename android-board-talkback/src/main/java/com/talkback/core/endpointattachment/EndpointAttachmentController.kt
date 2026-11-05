package com.talkback.core.endpointattachment

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.UUID

class EndpointAttachmentController(
    private val rateLimitWindowMs: Long = RATE_LIMIT_WINDOW_MS,
    private val dedupCapacity: Int = DEDUP_CAPACITY,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
    private val onLog: (String) -> Unit = {}
) {
    private val lastSendAtMsByKey = LinkedHashMap<String, Long>(16, 0.75f, true)
    private val recentMessageIds = object : LinkedHashMap<String, Long>(dedupCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > dedupCapacity
    }
    private data class InboundTransfer(
        val meta: EndpointAttachmentMetaPayload,
        val buffer: AttachmentReassemblyBuffer
    )

    private val inbound = HashMap<String, InboundTransfer>()

    fun prepareSend(
        from: EndpointAddress,
        to: EndpointAddress,
        data: ByteArray,
        kind: AttachmentKind,
        mimeType: String,
        fileName: String,
        caption: String?,
        messageId: String? = null,
        attachmentId: String? = null
    ): EndpointAttachmentPrepareResult {
        if (data.size > AttachmentChunker.MAX_ATTACHMENT_BYTES) {
            return EndpointAttachmentPrepareResult.Rejected(REASON_TOO_LARGE)
        }
        if (data.isEmpty()) {
            return EndpointAttachmentPrepareResult.Rejected(REASON_EMPTY)
        }
        val rateKey = rateLimitKey(from.key, to.key)
        val now = clockMs()
        val last = lastSendAtMsByKey[rateKey]
        if (last != null && now - last < rateLimitWindowMs) {
            onLog("ENDPOINT_ATTACHMENT rate_limited from=${from.key} to=${to.key}")
            return EndpointAttachmentPrepareResult.RateLimited
        }
        val id = messageId?.takeIf { it.isNotBlank() } ?: newMessageId()
        val attachId = attachmentId?.takeIf { it.isNotBlank() } ?: newMessageId()
        val totalChunks = AttachmentChunker.chunkCount(data.size)
        val meta = EndpointAttachmentMetaPayload(
            messageId = id,
            attachmentId = attachId,
            kind = kind,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            fileName = fileName.ifBlank { "attachment" },
            totalBytes = data.size,
            totalChunks = totalChunks,
            sha256 = AttachmentChunker.sha256Hex(data),
            caption = caption?.takeIf { it.isNotBlank() }
        )
        val chunks = ArrayList<PreparedChunk>(totalChunks)
        for (i in 0 until totalChunks) {
            val slice = AttachmentChunker.sliceChunk(data, i)
            chunks.add(
                PreparedChunk(
                    index = i,
                    payload = AttachmentChunker.encodeChunkPayload(attachId, i, totalChunks, slice)
                )
            )
        }
        return EndpointAttachmentPrepareResult.Ready(meta, chunks)
    }

    fun markSent(from: EndpointAddress, to: EndpointAddress) {
        lastSendAtMsByKey[rateLimitKey(from.key, to.key)] = clockMs()
    }

    fun onReceiveMeta(signal: SignalEnvelope): EndpointAttachmentMetaPayload? {
        if (signal.type != SignalType.ENDPOINT_ATTACHMENT_META) return null
        val payload = EndpointAttachmentMetaPayload.decode(signal.payload) ?: run {
            onLog("ENDPOINT_ATTACHMENT meta_invalid from=${signal.from.key}")
            return null
        }
        if (signal.to == null) return null
        synchronized(recentMessageIds) {
            if (recentMessageIds.containsKey(payload.messageId)) {
                onLog("ENDPOINT_ATTACHMENT meta_dedup messageId=${payload.messageId}")
                return null
            }
            recentMessageIds[payload.messageId] = clockMs()
        }
        synchronized(inbound) {
            inbound[payload.attachmentId] = InboundTransfer(
                meta = payload,
                buffer = AttachmentReassemblyBuffer(payload, onLog)
            )
        }
        onLog("ENDPOINT_ATTACHMENT meta_recv attachmentId=${payload.attachmentId} from=${signal.from.key}")
        return payload
    }

    fun onReceiveChunk(signal: SignalEnvelope): EndpointAttachmentEvent? {
        if (signal.type != SignalType.ENDPOINT_ATTACHMENT_CHUNK) return null
        val chunk = AttachmentChunker.decodeChunkPayload(signal.payload) ?: run {
            onLog("ENDPOINT_ATTACHMENT chunk_invalid from=${signal.from.key}")
            return null
        }
        val to = signal.to ?: return null
        val (meta, completeBytes) = synchronized(inbound) {
            val transfer = inbound[chunk.attachmentId] ?: run {
                onLog("ENDPOINT_ATTACHMENT chunk_orphan attachmentId=${chunk.attachmentId}")
                return null
            }
            val bytes = transfer.buffer.ingest(chunk.index, chunk.bytes) ?: return null
            inbound.remove(chunk.attachmentId)
            transfer.meta to bytes
        }
        onLog(
            "ENDPOINT_ATTACHMENT complete attachmentId=${meta.attachmentId} " +
                "from=${signal.from.key} bytes=${completeBytes.size}"
        )
        return EndpointAttachmentEvent(
            messageId = meta.messageId,
            attachmentId = meta.attachmentId,
            from = signal.from,
            to = to,
            kind = meta.kind,
            mimeType = meta.mimeType,
            fileName = meta.fileName,
            bytes = completeBytes,
            caption = meta.caption
        )
    }

    private fun rateLimitKey(sender: String, receiver: String): String =
        "$sender|$receiver|${SignalType.ENDPOINT_ATTACHMENT_META.name}"

    data class PreparedChunk(val index: Int, val payload: String)

    companion object {
        const val RATE_LIMIT_WINDOW_MS = 2_000L
        const val DEDUP_CAPACITY = 128
        const val REASON_TOO_LARGE = "ATTACHMENT_TOO_LARGE"
        const val REASON_EMPTY = "ATTACHMENT_EMPTY"
        const val REASON_UNREACHABLE = "UNREACHABLE"
        const val REASON_SEND_FAILED = "SEND_FAILED"
    }
}

sealed class EndpointAttachmentPrepareResult {
    data class Ready(
        val meta: EndpointAttachmentMetaPayload,
        val chunks: List<EndpointAttachmentController.PreparedChunk>
    ) : EndpointAttachmentPrepareResult()

    data object RateLimited : EndpointAttachmentPrepareResult()
    data class Rejected(val reason: String) : EndpointAttachmentPrepareResult()
}
