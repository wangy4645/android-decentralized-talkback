package com.talkback.core.endpointattachment

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.UUID

class ChannelAttachmentController(
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
        val meta: ChannelAttachmentMetaPayload,
        val buffer: ChannelAttachmentReassemblyBuffer
    )

    private val inbound = HashMap<String, InboundTransfer>()

    fun prepareSend(
        from: EndpointAddress,
        channelId: String,
        data: ByteArray,
        kind: AttachmentKind,
        mimeType: String,
        fileName: String,
        caption: String?,
        messageId: String? = null,
        attachmentId: String? = null
    ): ChannelAttachmentPrepareResult {
        if (channelId.isBlank()) {
            return ChannelAttachmentPrepareResult.Rejected(REASON_INVALID_CHANNEL)
        }
        if (data.size > AttachmentChunker.MAX_ATTACHMENT_BYTES) {
            return ChannelAttachmentPrepareResult.Rejected(REASON_TOO_LARGE)
        }
        if (data.isEmpty()) {
            return ChannelAttachmentPrepareResult.Rejected(REASON_EMPTY)
        }
        val rateKey = rateLimitKey(from.key, channelId)
        val now = clockMs()
        val last = lastSendAtMsByKey[rateKey]
        if (last != null && now - last < rateLimitWindowMs) {
            onLog("CHANNEL_ATTACHMENT rate_limited from=${from.key} channelId=$channelId")
            return ChannelAttachmentPrepareResult.RateLimited
        }
        val id = messageId?.takeIf { it.isNotBlank() } ?: newMessageId()
        val attachId = attachmentId?.takeIf { it.isNotBlank() } ?: newMessageId()
        val totalChunks = AttachmentChunker.chunkCount(data.size)
        val meta = ChannelAttachmentMetaPayload(
            messageId = id,
            attachmentId = attachId,
            channelId = channelId,
            kind = kind,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            fileName = fileName.ifBlank { "attachment" },
            totalBytes = data.size,
            totalChunks = totalChunks,
            sha256 = AttachmentChunker.sha256Hex(data),
            caption = caption?.takeIf { it.isNotBlank() }
        )
        val chunks = ArrayList<EndpointAttachmentController.PreparedChunk>(totalChunks)
        for (i in 0 until totalChunks) {
            val slice = AttachmentChunker.sliceChunk(data, i)
            chunks.add(
                EndpointAttachmentController.PreparedChunk(
                    index = i,
                    payload = AttachmentChunker.encodeChunkPayload(attachId, i, totalChunks, slice)
                )
            )
        }
        return ChannelAttachmentPrepareResult.Ready(meta, chunks)
    }

    fun markSent(from: EndpointAddress, channelId: String) {
        lastSendAtMsByKey[rateLimitKey(from.key, channelId)] = clockMs()
    }

    fun onReceiveMeta(signal: SignalEnvelope): ChannelAttachmentMetaPayload? {
        if (signal.type != SignalType.CHANNEL_ATTACHMENT_META) return null
        val payload = ChannelAttachmentMetaPayload.decode(signal.payload) ?: return null
        synchronized(recentMessageIds) {
            if (recentMessageIds.containsKey(payload.messageId)) return null
            recentMessageIds[payload.messageId] = clockMs()
        }
        synchronized(inbound) {
            inbound[payload.attachmentId] = InboundTransfer(
                meta = payload,
                buffer = ChannelAttachmentReassemblyBuffer(payload, onLog)
            )
        }
        return payload
    }

    fun onReceiveChunk(signal: SignalEnvelope): ChannelAttachmentEvent? {
        if (signal.type != SignalType.CHANNEL_ATTACHMENT_CHUNK) return null
        val chunk = AttachmentChunker.decodeChunkPayload(signal.payload) ?: return null
        val (meta, completeBytes) = synchronized(inbound) {
            val transfer = inbound[chunk.attachmentId] ?: return null
            val bytes = transfer.buffer.ingest(chunk.index, chunk.bytes) ?: return null
            inbound.remove(chunk.attachmentId)
            transfer.meta to bytes
        }
        return ChannelAttachmentEvent(
            messageId = meta.messageId,
            attachmentId = meta.attachmentId,
            channelId = meta.channelId,
            from = signal.from,
            kind = meta.kind,
            mimeType = meta.mimeType,
            fileName = meta.fileName,
            bytes = completeBytes,
            caption = meta.caption
        )
    }

    private fun rateLimitKey(sender: String, channelId: String): String =
        "$sender|$channelId|${SignalType.CHANNEL_ATTACHMENT_META.name}"

    companion object {
        const val RATE_LIMIT_WINDOW_MS = 2_000L
        const val DEDUP_CAPACITY = 128
        const val REASON_TOO_LARGE = "ATTACHMENT_TOO_LARGE"
        const val REASON_EMPTY = "ATTACHMENT_EMPTY"
        const val REASON_INVALID_CHANNEL = "INVALID_CHANNEL"
        const val REASON_NO_MEMBERS = "NO_MEMBERS"
        const val REASON_UNREACHABLE = "UNREACHABLE"
        const val REASON_SEND_FAILED = "SEND_FAILED"
    }
}

sealed class ChannelAttachmentPrepareResult {
    data class Ready(
        val meta: ChannelAttachmentMetaPayload,
        val chunks: List<EndpointAttachmentController.PreparedChunk>
    ) : ChannelAttachmentPrepareResult()

    data object RateLimited : ChannelAttachmentPrepareResult()
    data class Rejected(val reason: String) : ChannelAttachmentPrepareResult()
}
