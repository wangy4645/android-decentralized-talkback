package com.talkback.appprod.endpointtext

import android.content.Context
import java.io.File
import java.util.UUID

/** Persists inbound/outbound message attachment bytes under app-private storage. */
class MessageAttachmentStorage(private val rootDir: File) {

    init {
        rootDir.mkdirs()
    }

    fun writeBytes(attachmentId: String, fileName: String, bytes: ByteArray): File {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "attachment" }
        val file = File(rootDir, "${attachmentId}_$safeName")
        file.writeBytes(bytes)
        return file
    }

    fun fileForPath(path: String): File? {
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() && it.canonicalPath.startsWith(rootDir.canonicalPath) }
    }

    companion object {
        fun fromContext(context: Context): MessageAttachmentStorage =
            MessageAttachmentStorage(File(context.filesDir, "message_attachments"))

        fun newAttachmentId(): String = UUID.randomUUID().toString()
    }
}
