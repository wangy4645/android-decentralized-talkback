package com.talkback.app

import com.talkback.core.endpointattachment.AttachmentKind
import com.talkback.core.endpointattachment.bulk.AttachmentSizePolicy
import com.talkback.core.endpointattachment.bulk.BulkEndpointAttachmentController
import com.talkback.core.endpointattachment.bulk.BulkEndpointAttachmentSender
import com.talkback.core.endpointattachment.bulk.EndpointBulkAttachmentEvent
import com.talkback.core.endpointattachment.bulk.EndpointBulkOpenPayload
import com.talkback.core.endpointattachment.bulk.EndpointBulkReadyPayload
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.qos.IceConnectivity
import com.talkback.core.webrtc.WebRtcAudioEngine
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DataChannel bulk endpoint attachments (1:1). Wired from [TalkbackCoordinator].
 */
internal class BulkAttachmentCoordinatorBridge(
    private val onLog: (String) -> Unit,
    private val iceState: (String) -> String?,
    private val peerKeyForModule: (String) -> String?,
    private val getGroupEngine: (String) -> WebRtcAudioEngine?,
    private val isPttPaused: () -> Boolean,
    private val sendSignal: (peer: PeerTarget, envelope: SignalEnvelope) -> Boolean,
    private val buildEnvelope: (
        type: SignalType,
        from: EndpointAddress,
        to: EndpointAddress,
        payload: String,
    ) -> SignalEnvelope,
    private val resolvePeer: (String) -> PeerTarget?,
    private val isDialable: (String) -> Boolean,
    private val onBulkReceived: (EndpointBulkAttachmentEvent) -> Unit,
) {
    private var controller: BulkEndpointAttachmentController? = null
    private val wiredEngineModules = mutableSetOf<String>()
    private val readyWaiters = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    fun install(partialDir: File, finalDir: File) {
        controller = BulkEndpointAttachmentController(partialDir, finalDir, onLog = onLog)
    }

    fun onReceiveOpen(signal: SignalEnvelope) {
        val ctrl = controller ?: return
        val payload = ctrl.onReceiveOpen(signal) ?: return
        val moduleId = signal.from.moduleId.value
        val engine = getGroupEngine(moduleId) ?: return
        ensureBulkWired(engine, moduleId)
        engine.openBulkDataChannel()
        ctrl.drainPendingFrames(moduleId)?.let(onBulkReceived)
        val local = signal.to ?: return
        val peer = resolvePeer(moduleId) ?: return
        val ready = EndpointBulkReadyPayload(payload.attachmentId)
        val envelope = buildEnvelope(SignalType.ENDPOINT_BULK_READY, local, signal.from, ready.encode())
        if (!sendSignal(peer, envelope)) {
            onLog("BULK_ATTACHMENT ready_send_failed attachmentId=${payload.attachmentId}")
            return
        }
        onLog(
            "BULK_ATTACHMENT ready_sent attachmentId=${payload.attachmentId} " +
                "senderModule=$moduleId rosterKey=${peerKeyForModule(moduleId)}",
        )
    }

    fun onReceiveReady(signal: SignalEnvelope) {
        val payload = EndpointBulkReadyPayload.decode(signal.payload) ?: return
        val waiter = readyWaiters.remove(payload.attachmentId) ?: return
        waiter.complete(Unit)
        onLog("BULK_ATTACHMENT ready_recv attachmentId=${payload.attachmentId}")
    }

    fun onDataChannel(moduleId: String, frame: ByteArray) {
        val ctrl = controller ?: return
        val event = ctrl.onDataChannelFrame(moduleId, frame) ?: return
        onBulkReceived(event)
    }

    fun send(
        from: EndpointAddress,
        to: EndpointAddress,
        tempFile: File,
        totalBytes: Long,
        sha256: String,
        kind: AttachmentKind,
        mimeType: String,
        fileName: String,
        caption: String?,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<Unit> = runCatching {
        if (controller == null) error("BULK_NOT_INSTALLED")
        val pauseDeadline = System.currentTimeMillis() + com.talkback.core.video.VideoMediaPolicy.BULK_PAUSE_WAIT_MS
        while (isPttPaused()) {
            if (System.currentTimeMillis() > pauseDeadline) {
                error(BulkEndpointAttachmentController.REASON_PTT_PAUSED)
            }
            Thread.sleep(50)
        }
        when (AttachmentSizePolicy.route(totalBytes)) {
            AttachmentSizePolicy.Route.REJECT_TOO_LARGE ->
                error(BulkEndpointAttachmentController.REASON_TOO_LARGE)
            AttachmentSizePolicy.Route.REJECT_EMPTY ->
                error(BulkEndpointAttachmentController.REASON_EMPTY)
            AttachmentSizePolicy.Route.V2_UDP ->
                error("BULK_ROUTE_MISMATCH")
            AttachmentSizePolicy.Route.DC_BULK -> Unit
        }
        val moduleId = to.moduleId.value
        val ice = iceState(moduleId)
        if (!IceConnectivity.isConnected(ice)) {
            error(BulkEndpointAttachmentController.REASON_ICE_NOT_READY)
        }
        if (!isDialable(moduleId)) {
            error(BulkEndpointAttachmentController.REASON_UNREACHABLE)
        }
        val peer = resolvePeer(moduleId) ?: error(BulkEndpointAttachmentController.REASON_UNREACHABLE)
        val engine = getGroupEngine(moduleId) ?: error(BulkEndpointAttachmentController.REASON_ICE_NOT_READY)
        ensureBulkWired(engine, moduleId)
        engine.openBulkDataChannel()
        val messageId = BulkEndpointAttachmentController.newMessageId()
        val attachmentId = BulkEndpointAttachmentController.newMessageId()
        val open = EndpointBulkOpenPayload(
            messageId = messageId,
            attachmentId = attachmentId,
            kind = kind,
            mimeType = mimeType,
            fileName = fileName,
            totalBytes = totalBytes,
            sha256 = sha256,
            caption = caption,
        )
        val ready = CompletableFuture<Unit>()
        readyWaiters[attachmentId] = ready
        val envelope = buildEnvelope(SignalType.ENDPOINT_BULK_OPEN, from, to, open.encode())
        if (!sendSignal(peer, envelope)) {
            readyWaiters.remove(attachmentId)
            error(BulkEndpointAttachmentController.REASON_SEND_FAILED)
        }
        onProgress?.invoke(0L, totalBytes)
        val gotReady = runCatching {
            ready.get(READY_WAIT_MS, TimeUnit.MILLISECONDS)
            true
        }.getOrElse {
            readyWaiters.remove(attachmentId)
            false
        }
        if (!gotReady) {
            error(BulkEndpointAttachmentController.REASON_READY_TIMEOUT)
        }
        BulkEndpointAttachmentSender.sendFile(
            engine,
            tempFile,
            totalBytes,
            isPaused = isPttPaused,
            pauseWaitMs = com.talkback.core.video.VideoMediaPolicy.BULK_PAUSE_WAIT_MS,
            onProgress = onProgress,
        ).getOrThrow()
        onLog(
            "ENDPOINT_BULK sent attachmentId=$attachmentId to=${to.key} bytes=$totalBytes",
        )
    }

    private fun ensureBulkWired(engine: WebRtcAudioEngine, remoteModuleId: String) {
        if (!wiredEngineModules.add(remoteModuleId)) return
        engine.setBulkDataChannelMessageListener { frame ->
            onDataChannel(remoteModuleId, frame)
        }
    }

    companion object {
        private const val READY_WAIT_MS = 15_000L
    }
}
