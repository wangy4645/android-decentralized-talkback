package com.talkback.appprod.ui

import android.app.AlertDialog
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.talkback.appprod.R
import com.talkback.core.endpointattachment.AttachmentKind
import java.io.File

/**
 * Registers pickers / voice capture for conversation screens only.
 */
class ConversationAttachmentActions(
    fragment: Fragment,
    private val onBytesReady: (
        bytes: ByteArray,
        kind: AttachmentKind,
        mimeType: String,
        fileName: String
    ) -> Unit
) {
    private val context: Context = fragment.requireContext()
    private var voiceRecorder: MediaRecorder? = null
    private var voiceOutputFile: File? = null

    private val pickImage: ActivityResultLauncher<String> =
        fragment.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) deliverUri(uri, AttachmentKind.IMAGE, defaultName = "photo.jpg")
        }

    private val pickFile: ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) deliverUri(uri, AttachmentKind.FILE, defaultName = "file.bin")
        }

    fun showAttachMenu() {
        AlertDialog.Builder(context)
            .setTitle(R.string.message_attach_cd)
            .setItems(
                arrayOf(
                    context.getString(R.string.message_attach_pick_image),
                    context.getString(R.string.message_attach_pick_file),
                    context.getString(R.string.message_attach_record_voice)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickImage.launch("image/*")
                    1 -> pickFile.launch(arrayOf("*/*"))
                    2 -> toggleVoiceRecording()
                }
            }
            .show()
    }

    fun release() {
        runCatching {
            voiceRecorder?.stop()
        }
        voiceRecorder?.release()
        voiceRecorder = null
        voiceOutputFile = null
    }

    private fun toggleVoiceRecording() {
        if (voiceRecorder != null) {
            stopVoiceAndSend()
            return
        }
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        voiceOutputFile = file
        voiceRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        Toast.makeText(context, R.string.message_voice_recording_hint, Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceAndSend() {
        val file = voiceOutputFile ?: return
        runCatching { voiceRecorder?.stop() }
        voiceRecorder?.release()
        voiceRecorder = null
        voiceOutputFile = null
        if (!file.exists() || file.length() == 0L) return
        val bytes = file.readBytes()
        onBytesReady(bytes, AttachmentKind.VOICE, "audio/mp4", file.name)
    }

    private fun deliverUri(uri: Uri, kind: AttachmentKind, defaultName: String) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = queryDisplayName(uri) ?: defaultName
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            onBytesReady(bytes, kind, mime.ifBlank { "application/octet-stream" }, name)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) {
                return cursor.getString(idx)
            }
        }
        return null
    }
}
