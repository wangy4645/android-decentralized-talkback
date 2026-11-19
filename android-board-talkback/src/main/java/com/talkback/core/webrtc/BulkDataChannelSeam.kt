package com.talkback.core.webrtc

import com.talkback.core.endpointattachment.bulk.BulkAttachmentPolicy
import com.talkback.core.util.TalkbackLog
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer

/**
 * One labeled bulk DataChannel per peer connection (tb-bulk-v1).
 * Uses negotiated SCTP id so mesh audio SDP is not renegotiated for bulk send.
 */
class BulkDataChannelSeam(
    private val observedModuleId: String?,
) {
    @Volatile
    var messageListener: ((ByteArray) -> Unit)? = null

    private var channel: DataChannel? = null

    fun attachInbound(dc: DataChannel) {
        if (dc.label() != BulkAttachmentPolicy.DC_LABEL) return
        if (channel?.state() == DataChannel.State.OPEN) return
        wireChannel(dc)
    }

    fun openOutbound(peerConnection: PeerConnection): Boolean {
        channel?.let { existing ->
            when (existing.state()) {
                DataChannel.State.OPEN,
                DataChannel.State.CONNECTING,
                -> return true
                else -> close()
            }
        }
        val init = DataChannel.Init().apply {
            id = BulkAttachmentPolicy.DC_NEGOTIATED_ID
            negotiated = true
            ordered = true
        }
        val created = peerConnection.createDataChannel(BulkAttachmentPolicy.DC_LABEL, init)
        if (created == null) {
            log("BULK_DC create_failed module=$observedModuleId negotiatedId=${BulkAttachmentPolicy.DC_NEGOTIATED_ID}")
            return false
        }
        wireChannel(created)
        return true
    }

    fun send(bytes: ByteArray): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buf = DataChannel.Buffer(ByteBuffer.wrap(bytes), true)
        return dc.send(buf)
    }

    fun isOpen(): Boolean = channel?.state() == DataChannel.State.OPEN

    fun close() {
        channel?.unregisterObserver()
        channel?.close()
        channel = null
    }

    private fun wireChannel(dc: DataChannel) {
        channel?.unregisterObserver()
        channel = dc
        dc.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    log("BULK_DC state=${dc.state()} module=$observedModuleId negotiatedId=${BulkAttachmentPolicy.DC_NEGOTIATED_ID}")
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    messageListener?.invoke(data)
                }
            },
        )
        log(
            "BULK_DC wired state=${dc.state()} module=$observedModuleId negotiatedId=${BulkAttachmentPolicy.DC_NEGOTIATED_ID}",
        )
    }

    private fun log(msg: String) {
        TalkbackLog.i(msg)
    }
}