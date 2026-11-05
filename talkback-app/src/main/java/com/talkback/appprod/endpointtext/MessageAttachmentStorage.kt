package com.talkback.appprod.endpointtext

import android.content.Context
import com.talkback.core.endpointattachment.AttachmentChunker
import java.io.File
import java.util.UUID

/** Persists message attachment bytes under app-private storage with verified commit. */
class MessageAttachmentStorage(private val rootDir: File) {

    init {
        rootDir.mkdirs()
        File(rootDir, PARTIAL_SUBDIR).mkdirs()
    }

    /**
     * Writes [bytes] to a partial file, verifies [expectedSha256Hex], then renames to final path.
     * Returns null on any failure (partial file removed).
     */
    fun commitVerified(
        attachmentId: String,
        fileName: String,
        bytes: ByteArray,
        expectedSha256Hex: String
    ): File? {
        if (expectedSha256Hex.isBlank()) return null
        val actual = AttachmentChunker.sha256Hex(bytes)
        if (!actual.equals(expectedSha256Hex, ignoreCase = true)) return null
        val safeName = sanitizeFileName(fileName)
        val partialDir = File(rootDir, PARTIAL_SUBDIR)
        val partial = File(partialDir, "$attachmentId.partial")
        val final = File(rootDir, "${attachmentId}_$safeName")
        return runCatching {
            partial.writeBytes(bytes)
            if (final.exists()) final.delete()
            if (!partial.renameTo(final)) {
                partial.copyTo(final, overwrite = true)
                partial.delete()
            }
            final
        }.getOrElse {
            partial.delete()
            null
        }
    }

    enum class DeleteOutcome {
        Missing,
        RejectedPath,
        DeleteFailed,
        Deleted
    }

    data class DeleteAttempt(
        val outcome: DeleteOutcome,
        val diagnostic: String = ""
    )

    fun deleteFileAtPath(path: String): DeleteOutcome = tryDeleteFileAtPath(path).outcome

    fun tryDeleteFileAtPath(path: String): DeleteAttempt {
        if (path.isBlank()) {
            return DeleteAttempt(DeleteOutcome.Missing, "blank_path")
        }
        val file = File(path)
        if (!file.exists()) {
            return DeleteAttempt(DeleteOutcome.Missing, "not_found")
        }
        if (!file.canonicalPath.startsWith(rootDir.canonicalPath)) {
            return DeleteAttempt(DeleteOutcome.RejectedPath, "outside_attachment_root")
        }
        return if (file.delete()) {
            DeleteAttempt(DeleteOutcome.Deleted)
        } else {
            DeleteAttempt(
                DeleteOutcome.DeleteFailed,
                "delete_returned_false exists=${file.exists()} canWrite=${file.canWrite()}"
            )
        }
    }

    /** Removes stale partial files under [PARTIAL_SUBDIR]. */
    fun sweepPartialFiles(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val partialDir = File(rootDir, PARTIAL_SUBDIR)
        partialDir.listFiles()?.forEach { file ->
            if (nowMs - file.lastModified() > maxAgeMs) {
                file.delete()
            }
        }
    }

    fun fileForPath(path: String): File? {
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() && it.canonicalPath.startsWith(rootDir.canonicalPath) }
    }

    companion object {
        private const val PARTIAL_SUBDIR = ".partial"

        fun fromContext(context: Context): MessageAttachmentStorage =
            MessageAttachmentStorage(File(context.filesDir, "message_attachments"))

        fun newAttachmentId(): String = UUID.randomUUID().toString()

        fun sanitizeFileName(fileName: String): String =
            fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "attachment" }

        /** Log-safe identifier (attachment id prefix); omits user file name suffix. */
        fun logRefForLocalPath(path: String): String {
            if (path.isBlank()) return "attachmentKey=unknown"
            val name = File(path).name
            if (name.isBlank()) return "attachmentKey=unknown"
            return "attachmentKey=${name.substringBefore('_')}"
        }
    }
}
