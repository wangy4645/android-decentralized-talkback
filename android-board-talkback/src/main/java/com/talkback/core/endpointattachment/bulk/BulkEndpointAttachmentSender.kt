package com.talkback.core.endpointattachment.bulk

import com.talkback.core.webrtc.WebRtcAudioEngine
import java.io.File
import java.io.FileInputStream

object BulkEndpointAttachmentSender {
    fun sendFile(
        engine: WebRtcAudioEngine,
        file: File,
        expectedBytes: Long,
        waitOpenMs: Long = 20_000L,
        paceEveryFrames: Int = 4,
        paceMs: Long = 1L,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<Unit> = runCatching {
        require(file.isFile)
        require(file.length() == expectedBytes)
        if (!engine.openBulkDataChannel()) {
            error(BulkEndpointAttachmentController.REASON_DC_OPEN_FAILED)
        }
        val deadline = System.currentTimeMillis() + waitOpenMs
        while (!engine.isBulkDataChannelOpen()) {
            if (System.currentTimeMillis() > deadline) {
                error(BulkEndpointAttachmentController.REASON_DC_OPEN_FAILED)
            }
            Thread.sleep(20)
        }
        onProgress?.invoke(0L, expectedBytes)
        val buf = ByteArray(BulkAttachmentPolicy.DATA_FRAME_MAX_BYTES)
        var sent = 0L
        var frames = 0
        var lastReportedPct = -1
        FileInputStream(file).use { input ->
            while (sent < expectedBytes) {
                val toRead = minOf(buf.size.toLong(), expectedBytes - sent).toInt()
                val read = input.read(buf, 0, toRead)
                if (read <= 0) error(BulkEndpointAttachmentController.REASON_SEND_FAILED)
                val frame = BulkAttachmentFrameCodec.encodeDataFrame(buf.copyOf(read))
                if (!engine.sendBulkDataChannel(frame)) {
                    error(BulkEndpointAttachmentController.REASON_SEND_FAILED)
                }
                sent += read
                frames++
                val pct = if (expectedBytes <= 0L) 100 else ((sent * 100L) / expectedBytes).toInt()
                if (pct != lastReportedPct && (pct == 100 || pct - lastReportedPct >= 2 || frames % 8 == 0)) {
                    lastReportedPct = pct
                    onProgress?.invoke(sent, expectedBytes)
                }
                if (paceEveryFrames > 0 && frames % paceEveryFrames == 0) {
                    Thread.sleep(paceMs)
                }
            }
        }
        if (!engine.sendBulkDataChannel(BulkAttachmentFrameCodec.encodeEndFrame())) {
            error(BulkEndpointAttachmentController.REASON_SEND_FAILED)
        }
        onProgress?.invoke(expectedBytes, expectedBytes)
    }
}