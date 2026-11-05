package com.talkback.appprod.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.talkback.appprod.R
import com.talkback.appprod.endpointtext.ChannelTextRecord
import com.talkback.appprod.endpointtext.EndpointTextRecord
import com.talkback.appprod.endpointtext.MessageAttachmentInfo
import com.talkback.core.endpointattachment.AttachmentKind
import java.io.File

object MessageRowUiBinder {

    fun bindDirectBody(
        context: Context,
        body: TextView,
        attachmentContainer: LinearLayout,
        attachmentPreview: ImageView,
        attachmentAction: TextView,
        record: EndpointTextRecord
    ) {
        bindAttachment(
            context,
            body,
            attachmentContainer,
            attachmentPreview,
            attachmentAction,
            record.text,
            record.attachment
        )
    }

    fun bindChannelBody(
        context: Context,
        body: TextView,
        attachmentContainer: LinearLayout,
        attachmentPreview: ImageView,
        attachmentAction: TextView,
        record: ChannelTextRecord
    ) {
        bindAttachment(
            context,
            body,
            attachmentContainer,
            attachmentPreview,
            attachmentAction,
            record.text,
            record.attachment
        )
    }

    private fun bindAttachment(
        context: Context,
        body: TextView,
        attachmentContainer: LinearLayout,
        attachmentPreview: ImageView,
        attachmentAction: TextView,
        caption: String,
        attachment: MessageAttachmentInfo?
    ) {
        body.text = caption
        body.isVisible = caption.isNotBlank()
        if (attachment == null) {
            attachmentContainer.isVisible = false
            return
        }
        attachmentContainer.isVisible = true
        attachmentPreview.isVisible = false
        when (attachment.kind) {
            AttachmentKind.IMAGE -> {
                val file = File(attachment.localPath)
                if (file.exists()) {
                    attachmentPreview.isVisible = true
                    attachmentPreview.setImageBitmap(
                        BitmapFactory.decodeFile(file.absolutePath)
                    )
                }
                attachmentAction.text = context.getString(R.string.message_attachment_open_image)
                attachmentAction.setOnClickListener { openFile(context, attachment) }
            }
            AttachmentKind.VOICE -> {
                attachmentAction.text = context.getString(R.string.message_attachment_play_voice)
                attachmentAction.setOnClickListener { playVoice(context, attachment) }
            }
            AttachmentKind.FILE -> {
                attachmentAction.text = context.getString(
                    R.string.message_attachment_open_file,
                    attachment.fileName
                )
                attachmentAction.setOnClickListener { openFile(context, attachment) }
            }
        }
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
            attachmentPreview = row.findViewById(R.id.imgMessageAttachment),
            attachmentAction = row.findViewById(R.id.btnMessageAttachmentAction)
        )
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
            file
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
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { release() }
            }
        }.onFailure {
            Toast.makeText(context, R.string.message_attachment_play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    data class MessageRowViews(
        val row: View,
        val bubble: LinearLayout,
        val sender: TextView,
        val time: TextView,
        val body: TextView,
        val attachmentContainer: LinearLayout,
        val attachmentPreview: ImageView,
        val attachmentAction: TextView
    )
}
