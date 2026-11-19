package com.talkback.appprod.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.talkback.appprod.R
import com.talkback.appprod.endpointtext.AttachmentTransferStage
import com.talkback.appprod.endpointtext.ChannelTextRecord
import com.talkback.appprod.endpointtext.EndpointTextRecord
import com.talkback.appprod.endpointtext.MessageAttachmentInfo
import com.talkback.core.endpointattachment.AttachmentKind
import com.talkback.core.endpointattachment.LocationFixPayload
import java.io.File

object MessageRowUiBinder {

    private val previewCache = object : LruCache<String, Bitmap>(8) {}

    fun bindDirectBody(
        context: Context,
        body: TextView,
        attachmentContainer: LinearLayout,
        attachmentPreview: ImageView,
        attachmentVoice: View,
        attachmentLocation: View,
        attachmentLocationText: TextView,
        attachmentAction: TextView,
        attachmentProgress: TextView,
        record: EndpointTextRecord,
        imageContainer: FrameLayout? = null,
        imageProgressOverlay: View? = null,
        imageProgressBar: ProgressBar? = null,
        imageProgressText: TextView? = null,
    ) {
        bindAttachment(
            context,
            body,
            attachmentContainer,
            attachmentPreview,
            attachmentVoice,
            attachmentLocation,
            attachmentLocationText,
            attachmentAction,
            attachmentProgress,
            record.text,
            record.attachment,
            imageContainer,
            imageProgressOverlay,
            imageProgressBar,
            imageProgressText,
        )
    }

    fun bindChannelBody(
        context: Context,
        body: TextView,
        attachmentContainer: LinearLayout,
        attachmentPreview: ImageView,
        attachmentVoice: View,
        attachmentLocation: View,
        attachmentLocationText: TextView,
        attachmentAction: TextView,
        attachmentProgress: TextView,
        record: ChannelTextRecord,
        imageContainer: FrameLayout? = null,
        imageProgressOverlay: View? = null,
        imageProgressBar: ProgressBar? = null,
        imageProgressText: TextView? = null,
    ) {
        bindAttachment(
            context,
            body,
            attachmentContainer,
            attachmentPreview,
            attachmentVoice,
            attachmentLocation,
            attachmentLocationText,
            attachmentAction,
            attachmentProgress,
            record.text,
            record.attachment,
            imageContainer,
            imageProgressOverlay,
            imageProgressBar,
            imageProgressText,
        )
    }

    private fun bindAttachment(
        context: Context,
        body: TextView,
        attachmentContainer: LinearLayout,
        attachmentPreview: ImageView,
        attachmentVoice: View,
        attachmentLocation: View,
        attachmentLocationText: TextView,
        attachmentAction: TextView,
        attachmentProgress: TextView,
        caption: String,
        attachment: MessageAttachmentInfo?,
        imageContainer: FrameLayout?,
        imageProgressOverlay: View?,
        imageProgressBar: ProgressBar?,
        imageProgressText: TextView?,
    ) {
        body.text = caption
        body.isVisible = caption.isNotBlank()
        attachmentPreview.setOnClickListener(null)
        attachmentVoice.setOnClickListener(null)
        attachmentLocation.setOnClickListener(null)
        attachmentAction.setOnClickListener(null)
        attachmentVoice.isVisible = false
        attachmentLocation.isVisible = false
        attachmentAction.isVisible = false
        attachmentProgress.isVisible = false
        imageContainer?.isVisible = false
        imageProgressOverlay?.isVisible = false
        attachmentPreview.isVisible = false
        if (attachment == null) {
            attachmentContainer.isVisible = false
            return
        }
        attachmentContainer.isVisible = true
        val transferring = attachment.transferFailed ||
            attachment.transferProgressPercent != null ||
            attachment.transferStage != null
        when (attachment.kind) {
            AttachmentKind.IMAGE -> {
                val container = imageContainer
                if (container != null) {
                    container.isVisible = true
                    attachmentPreview.isVisible = true
                } else {
                    attachmentPreview.isVisible = true
                }
                bindImagePreview(attachmentPreview, attachment.localPath)
                if (!attachment.transferFailed && !transferring) {
                    attachmentPreview.setOnClickListener { openFile(context, attachment) }
                }
                if (transferring) {
                    bindImageTransferOverlay(
                        context,
                        imageProgressOverlay,
                        imageProgressBar,
                        imageProgressText,
                        attachment,
                    )
                }
            }
            AttachmentKind.VOICE -> {
                attachmentVoice.isVisible = true
                if (!transferring) {
                    attachmentVoice.setOnClickListener { playVoice(context, attachment) }
                }
            }
            AttachmentKind.FILE -> {
                attachmentAction.isVisible = true
                attachmentAction.text = context.getString(
                    R.string.message_attachment_open_file,
                    attachment.fileName,
                )
                if (!transferring) {
                    attachmentAction.setOnClickListener { openFile(context, attachment) }
                }
            }
            AttachmentKind.LOCATION -> {
                attachmentLocation.isVisible = true
                attachmentLocationText.text = locationLabel(context, attachment)
                if (!transferring) {
                    attachmentLocation.setOnClickListener { openLocation(context, attachment) }
                }
            }
        }
        if (attachment.kind != AttachmentKind.IMAGE) {
            when {
                attachment.transferFailed -> {
                    attachmentProgress.isVisible = true
                    attachmentProgress.text =
                        context.getString(R.string.message_attachment_send_failed_inline)
                    attachmentProgress.setTextColor(context.getColor(R.color.tb_danger))
                }
                transferring -> {
                    attachmentProgress.isVisible = true
                    attachmentProgress.text = transferLabel(context, attachment)
                    attachmentProgress.setTextColor(context.getColor(R.color.tb_text_secondary))
                }
            }
        }
    }

    private fun bindImageTransferOverlay(
        context: Context,
        overlay: View?,
        bar: ProgressBar?,
        label: TextView?,
        attachment: MessageAttachmentInfo,
    ) {
        if (overlay == null || bar == null || label == null) return
        overlay.isVisible = true
        if (attachment.transferFailed) {
            bar.isIndeterminate = false
            bar.progress = 0
            label.text = context.getString(R.string.message_attachment_send_failed_inline)
            return
        }
        when (attachment.transferStage) {
            AttachmentTransferStage.PREPARING,
            AttachmentTransferStage.WAITING_PEER,
            -> {
                bar.isIndeterminate = true
            }
            AttachmentTransferStage.SENDING,
            null,
            -> {
                bar.isIndeterminate = false
                bar.progress = attachment.transferProgressPercent?.coerceIn(0, 100) ?: 0
            }
        }
        label.text = transferLabel(context, attachment)
    }

    private fun transferLabel(context: Context, attachment: MessageAttachmentInfo): String {
        if (attachment.transferFailed) {
            return context.getString(R.string.message_attachment_send_failed_inline)
        }
        return when (attachment.transferStage) {
            AttachmentTransferStage.PREPARING ->
                context.getString(R.string.message_attachment_preparing)
            AttachmentTransferStage.WAITING_PEER ->
                context.getString(R.string.message_attachment_waiting_peer)
            AttachmentTransferStage.SENDING,
            null,
            -> context.getString(
                R.string.message_attachment_sending_progress,
                attachment.transferProgressPercent?.coerceIn(0, 100) ?: 0,
            )
        }
    }

    private fun bindImagePreview(imageView: ImageView, path: String) {
        val file = File(path)
        if (!file.exists()) {
            imageView.setImageDrawable(null)
            return
        }
        val cached = previewCache.get(path)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }
        val decoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, 400, 280)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
        if (decoded != null) {
            previewCache.put(path, decoded)
            imageView.setImageBitmap(decoded)
        } else {
            imageView.setImageDrawable(null)
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (height > reqH || width > reqW) {
            var halfH = height / 2
            var halfW = width / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) {
                sample *= 2
            }
        }
        return sample.coerceAtLeast(1)
    }

    fun inflateMessageRow(inflater: LayoutInflater, parent: LinearLayout): MessageRowViews {
        val row = inflater.inflate(R.layout.item_conversation_message, parent, false)
        return MessageRowViews(
            row = row,
            bubble = row.findViewById(R.id.bubbleRoot),
            sender = row.findViewById(R.id.txtMessageSender),
            time = row.findViewById(R.id.txtMessageTime),
            body = row.findViewById(R.id.txtMessageBody),
            attachmentContainer = row.findViewById(R.id.containerMessageAttachment),
            imageContainer = row.findViewById(R.id.containerMessageImage),
            attachmentPreview = row.findViewById(R.id.imgMessageAttachment),
            imageProgressOverlay = row.findViewById(R.id.containerMessageImageProgress),
            imageProgressBar = row.findViewById(R.id.progressMessageAttachment),
            imageProgressText = row.findViewById(R.id.txtMessageImageProgress),
            attachmentVoice = row.findViewById(R.id.containerMessageVoice),
            attachmentLocation = row.findViewById<View>(R.id.containerMessageLocation).also { loc ->
                loc.clipToOutline = true
            },
            attachmentLocationText = row.findViewById(R.id.txtMessageLocation),
            attachmentProgress = row.findViewById(R.id.txtMessageAttachmentProgress),
            attachmentAction = row.findViewById(R.id.btnMessageAttachmentAction),
        )
    }

    private fun locationLabel(context: Context, attachment: MessageAttachmentInfo): String {
        val payload = decodeLocation(attachment)
            ?: return context.getString(R.string.message_attach_pick_location)
        val acc = payload.accuracyMeters
        val coords = if (acc != null) {
            context.getString(
                R.string.message_location_coords_acc,
                payload.latitude,
                payload.longitude,
                acc,
            )
        } else {
            context.getString(
                R.string.message_location_coords,
                payload.latitude,
                payload.longitude,
            )
        }
        return context.getString(R.string.message_attach_pick_location) + "\n" + coords
    }

    private fun openLocation(context: Context, attachment: MessageAttachmentInfo) {
        val payload = decodeLocation(attachment)
        if (payload == null) {
            Toast.makeText(context, R.string.message_attachment_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = android.net.Uri.parse(
            "geo:${payload.latitude},${payload.longitude}?q=${payload.latitude},${payload.longitude}",
        )
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.message_location_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun decodeLocation(attachment: MessageAttachmentInfo): LocationFixPayload? {
        val file = File(attachment.localPath)
        if (!file.exists()) return null
        return LocationFixPayload.decode(file.readBytes())
    }

    private fun openFile(context: Context, attachment: MessageAttachmentInfo) {
        val file = File(attachment.localPath)
        if (!file.exists()) {
            Toast.makeText(context, R.string.message_attachment_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.message_attachment_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun playVoice(context: Context, attachment: MessageAttachmentInfo) {
        val file = File(attachment.localPath)
        if (!file.exists()) {
            Toast.makeText(context, R.string.message_attachment_missing, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            voicePlayer?.release()
            voicePlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    release()
                    if (voicePlayer === this) voicePlayer = null
                }
            }
        }.onFailure {
            Toast.makeText(context, R.string.message_attachment_play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private var voicePlayer: MediaPlayer? = null

    data class MessageRowViews(
        val row: View,
        val bubble: LinearLayout,
        val sender: TextView,
        val time: TextView,
        val body: TextView,
        val attachmentContainer: LinearLayout,
        val imageContainer: FrameLayout,
        val attachmentPreview: ImageView,
        val imageProgressOverlay: View,
        val imageProgressBar: ProgressBar,
        val imageProgressText: TextView,
        val attachmentVoice: View,
        val attachmentLocation: View,
        val attachmentLocationText: TextView,
        val attachmentProgress: TextView,
        val attachmentAction: TextView,
    )
}
