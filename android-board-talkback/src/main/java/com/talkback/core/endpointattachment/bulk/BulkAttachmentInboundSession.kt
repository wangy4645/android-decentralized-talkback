package com.talkback.core.endpointattachment.bulk

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Streams inbound DC frames into a partial file; verifies SHA-256 on END.
 */
class BulkAttachmentInboundSession(
    val meta: EndpointBulkOpenPayload,
    private val partialFile: File,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    @Volatile
    var receivedBytes: Long = 0
        private set
    private var closed = false

    fun ingestData(payload: ByteArray): Boolean {
        if (closed) return false
        if (receivedBytes + payload.size > meta.totalBytes) return false
        RandomAccessFile(partialFile, "rw").use { raf ->
            raf.seek(receivedBytes)
            raf.write(payload)
        }
        digest.update(payload)
        receivedBytes += payload.size
        return true
    }

    /** @return final file if complete and SHA matches; null on failure. */
    fun complete(finalFile: File): File? {
        if (closed) return null
        closed = true
        if (receivedBytes != meta.totalBytes) {
            partialFile.delete()
            return null
        }
        val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
        if (!actual.equals(meta.sha256, ignoreCase = true)) {
            partialFile.delete()
            return null
        }
        if (finalFile.exists()) finalFile.delete()
        return if (partialFile.renameTo(finalFile)) {
            finalFile
        } else {
            runCatching {
                partialFile.copyTo(finalFile, overwrite = true)
                partialFile.delete()
                finalFile
            }.getOrNull()
        }
    }

    fun abort() {
        closed = true
        partialFile.delete()
    }
}