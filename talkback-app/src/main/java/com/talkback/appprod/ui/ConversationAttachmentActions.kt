package com.talkback.appprod.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
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
    private val fragment: Fragment,
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
    private var voiceStartedAtMs: Long = 0L

    private val pickImage: ActivityResultLauncher<Intent> =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finishPickerSession()
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            deliverUri(uri, AttachmentKind.IMAGE, defaultName = "photo.jpg")
        }

    private val pickDocument: ActivityResultLauncher<Intent> =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finishPickerSession()
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            deliverUri(uri, AttachmentKind.FILE, defaultName = "file.bin")
        }

    fun pickImage() {
        beginPickerSession()
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        runCatching { pickImage.launch(intent) }.onFailure { finishPickerSession() }
    }

    fun pickFile() {
        beginPickerSession()
        launchSystemFilePicker()
    }

    private fun launchSystemFilePicker() {
        val intent = buildDocumentPickIntent()
        runCatching {
            pickDocument.launch(intent)
        }.onFailure {
            finishPickerSession()
            Toast.makeText(context, R.string.message_attach_file_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Document-oriented SAF: no catch-all mime (Huawei opens image/Screenshot grid). Images use [pickImage]. */
    private fun buildDocumentPickIntent(): Intent {
        val mimeTypes = arrayOf(
            "application/*",
            "text/*",
        )
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentPickStartUri())
            }
        }
    }

    private fun documentPickStartUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
        }
    }

    fun isVoiceRecording(): Boolean = voiceRecorder != null

    /** Hold-to-talk: returns false if recorder could not start. */
    fun startVoiceRecording(): Boolean {
        if (voiceRecorder != null) return true
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        voiceOutputFile = file
        return runCatching {
            voiceRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            voiceStartedAtMs = System.currentTimeMillis()
            true
        }.getOrElse {
            releaseVoiceRecorder(deleteFile = true)
            Toast.makeText(context, R.string.message_voice_record_start_failed, Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** Release finger: stop and send if long enough. */
    fun finishVoiceRecordingAndSend() {
        val file = voiceOutputFile ?: return
        val durationMs = System.currentTimeMillis() - voiceStartedAtMs
        releaseVoiceRecorder(deleteFile = false)
        if (durationMs < MIN_VOICE_MS) {
            file.delete()
            Toast.makeText(context, R.string.message_voice_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        val bytes = file.readBytes()
        onBytesReady(bytes, AttachmentKind.VOICE, "audio/mp4", file.name)
    }

    fun cancelVoiceRecording() {
        releaseVoiceRecorder(deleteFile = true)
    }

    fun release() {
        finishPickerSession()
        releaseVoiceRecorder(deleteFile = true)
    }

    private fun beginPickerSession() {
        ConversationAttachmentPickerGate.setActive(true)
    }

    private fun finishPickerSession() {
        ConversationAttachmentPickerGate.setActive(false)
    }

    private fun releaseVoiceRecorder(deleteFile: Boolean) {
        runCatching { voiceRecorder?.stop() }
        runCatching { voiceRecorder?.release() }
        voiceRecorder = null
        voiceOutputFile?.let { file ->
            if (deleteFile) file.delete()
        }
        voiceOutputFile = null
        voiceStartedAtMs = 0L
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

    companion object {
        private const val MIN_VOICE_MS = 600L
    }
}
