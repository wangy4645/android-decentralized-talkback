package com.talkback.core.endpointattachment.bulk

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BulkEndpointAttachmentController(
    private val partialDir: File,
    private val finalDir: File,
    private val onLog: (String) -> Unit = {},
) {
    private data class InboundKey(val attachmentId: String, val senderModuleId: String)

    private data class InboundState(
        val session: BulkAttachmentInboundSession,
        val from: EndpointAddress,
        val to: EndpointAddress,
    )

    private val inbound = ConcurrentHashMap<InboundKey, InboundState>()
    private val pendingFramesByModule = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()

    init {
        partialDir.mkdirs()
        finalDir.mkdirs()
    }

    fun onReceiveOpen(signal: SignalEnvelope): EndpointBulkOpenPayload? {
        if (signal.type != SignalType.ENDPOINT_BULK_OPEN) return null
        val payload = EndpointBulkOpenPayload.decode(signal.payload) ?: run {
            onLog("BULK_ATTACHMENT open_invalid from=${signal.from.key}")
            return null
        }
        if (signal.to == null) return null
        val moduleId = signal.from.moduleId.value
        val key = InboundKey(payload.attachmentId, moduleId)
        cancelInbound(key)
        val partial = File(partialDir, "${payload.attachmentId}.partial")
        partial.delete()
        inbound[key] = InboundState(
            session = BulkAttachmentInboundSession(payload, partial),
            from = signal.from,
            to = signal.to,
        )
        onLog(
            "BULK_ATTACHMENT open_recv attachmentId=${payload.attachmentId} " +
                "bytes=${payload.totalBytes} senderModule=$moduleId from=${signal.from.key}",
        )
        return payload
    }

    fun drainPendingFrames(senderModuleId: String): EndpointBulkAttachmentEvent? {
        val queued = synchronized(pendingLock(senderModuleId)) {
            pendingFramesByModule.remove(senderModuleId)
        } ?: return null
        var event: EndpointBulkAttachmentEvent? = null
        for (frame in queued) {
            event = ingestFrame(senderModuleId, frame) ?: event
        }
        return event
    }

    fun onDataChannelFrame(senderModuleId: String, frame: ByteArray): EndpointBulkAttachmentEvent? =
        ingestFrame(senderModuleId, frame)

    private fun ingestFrame(senderModuleId: String, frame: ByteArray): EndpointBulkAttachmentEvent? {
        val parsed = BulkAttachmentFrameCodec.parseFrame(frame)
        if (parsed == null) {
            onLog("BULK_ATTACHMENT parse_fail senderModule=$senderModuleId bytes=${frame.size}")
            return null
        }
        val entry = inbound.entries.firstOrNull { it.key.senderModuleId == senderModuleId }
        if (entry == null) {
            onLog("BULK_ATTACHMENT no_session senderModule=$senderModuleId frameBytes=${frame.size}")
            bufferPending(senderModuleId, frame)
            return null
        }
        val key = entry.key
        val state = entry.value
        val session = state.session
        return when (parsed) {
            is BulkAttachmentFrameCodec.Parsed.Data -> {
                if (!session.ingestData(parsed.payload)) {
                    onLog(
                        "BULK_ATTACHMENT ingest_reject attachmentId=${key.attachmentId} " +
                            "senderModule=$senderModuleId chunk=${parsed.payload.size}",
                    )
                    cancelInbound(key)
                } else {
                    val received = session.receivedBytes
                    if (received == parsed.payload.size.toLong() ||
                        received % PROGRESS_LOG_BYTES < parsed.payload.size
                    ) {
                        onLog(
                            "BULK_ATTACHMENT ingest_data attachmentId=${key.attachmentId} " +
                                "senderModule=$senderModuleId received=$received/${session.meta.totalBytes}",
                        )
                    }
                }
                null
            }
            BulkAttachmentFrameCodec.Parsed.End -> {
                val meta = session.meta
                onLog(
                    "BULK_ATTACHMENT ingest_end attachmentId=${meta.attachmentId} " +
                        "senderModule=$senderModuleId received=${session.receivedBytes}/${meta.totalBytes}",
                )
                val safeName = sanitizeFileName(meta.fileName)
                val final = File(finalDir, "${meta.attachmentId}_$safeName")
                val file = session.complete(final) ?: run {
                    cancelInbound(key)
                    onLog("BULK_ATTACHMENT inbound_fail attachmentId=${meta.attachmentId}")
                    return null
                }
                inbound.remove(key)
                onLog("BULK_ATTACHMENT inbound_ok attachmentId=${meta.attachmentId} file=${file.name}")
                EndpointBulkAttachmentEvent(
                    messageId = meta.messageId,
                    attachmentId = meta.attachmentId,
                    from = state.from,
                    to = state.to,
                    kind = meta.kind,
                    mimeType = meta.mimeType,
                    fileName = meta.fileName,
                    localFile = file,
                    caption = meta.caption,
                    sha256 = meta.sha256,
                )
            }
        }
    }

    fun onPeerDisconnected(senderModuleId: String) {
        val keys = inbound.keys.filter { it.senderModuleId == senderModuleId }
        keys.forEach { cancelInbound(it) }
        pendingFramesByModule.remove(senderModuleId)
    }

    private fun bufferPending(senderModuleId: String, frame: ByteArray) {
        synchronized(pendingLock(senderModuleId)) {
            val queue = pendingFramesByModule.getOrPut(senderModuleId) { ArrayDeque() }
            if (queue.size < MAX_PENDING_FRAMES) {
                queue.addLast(frame)
            }
        }
    }

    private fun pendingLock(senderModuleId: String): Any = senderModuleId.intern()

    private fun cancelInbound(key: InboundKey) {
        inbound.remove(key)?.session?.abort()
    }

    companion object {
        fun newMessageId(): String = UUID.randomUUID().toString()

        fun sanitizeFileName(fileName: String): String =
            fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "attachment" }

        const val REASON_ICE_NOT_READY = "BULK_ICE_NOT_READY"
        const val REASON_TOO_LARGE = "BULK_TOO_LARGE"
        const val REASON_EMPTY = "BULK_EMPTY"
        const val REASON_PTT_PAUSED = "BULK_PTT_PAUSED"
        const val REASON_DC_OPEN_FAILED = "BULK_DC_OPEN_FAILED"
        const val REASON_READY_TIMEOUT = "BULK_READY_TIMEOUT"
        const val REASON_SEND_FAILED = "BULK_SEND_FAILED"
        const val REASON_UNREACHABLE = "UNREACHABLE"
        private const val MAX_PENDING_FRAMES = 256
        private const val PROGRESS_LOG_BYTES = 512 * 1024L
    }
}
