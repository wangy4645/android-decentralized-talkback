package com.talkback.appprod.endpointtext

import android.content.Context
import com.talkback.core.endpointattachment.AttachmentChunker
import com.talkback.core.endpointattachment.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InboundAttachmentPersistenceTest {

    private lateinit var context: Context
    private lateinit var persistence: MessagePersistence
    private lateinit var storage: MessageAttachmentStorage
    private lateinit var store: ConversationStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("tactical_messages.db")
        persistence = MessagePersistence(context)
        storage = MessageAttachmentStorage(File(context.filesDir, "test_message_attachments_${System.nanoTime()}"))
        store = ConversationStore(persistence)
    }

    @Test
    fun dbInsertFailure_deletesNewlyCommittedFile() {
        val bytes = "hello-attachment".toByteArray()
        val sha = AttachmentChunker.sha256Hex(bytes)
        val attachmentId = MessageAttachmentStorage.newAttachmentId()
        val file = storage.commitVerified(attachmentId, "note.txt", bytes, sha)
        assertNotNull(file)
        persistence.testForceNextDirectInsertFailure = true

        val ok = InboundAttachmentPersistence.persistAfterCommit(
            attachmentLocalPath = file!!.absolutePath,
            storage = storage,
            persistence = persistence
        ) {
            store.append(
                inboundAttachmentRecord(
                    endpointKey = "peer-a",
                    path = file.absolutePath,
                    sizeBytes = file.length()
                )
            )
        }

        assertFalse(ok)
        assertTrue(store.recent("peer-a").isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun dbInsertFailure_keepsFileWhenOtherMessageReferencesSamePath() {
        val bytes = "shared-bytes".toByteArray()
        val sha = AttachmentChunker.sha256Hex(bytes)
        val attachmentId = MessageAttachmentStorage.newAttachmentId()
        val file = storage.commitVerified(attachmentId, "shared.bin", bytes, sha)
        assertNotNull(file)
        val path = file!!.absolutePath

        assertTrue(
            store.append(
                inboundAttachmentRecord("peer-a", path, file.length(), timestampMs = 1L)
            )
        )
        assertEquals(1, persistence.countAttachmentPathReferences(path))

        persistence.testForceNextDirectInsertFailure = true
        val ok = InboundAttachmentPersistence.persistAfterCommit(
            attachmentLocalPath = path,
            storage = storage,
            persistence = persistence
        ) {
            store.append(
                inboundAttachmentRecord(
                    endpointKey = "peer-b",
                    path = path,
                    sizeBytes = file.length(),
                    timestampMs = 2L
                )
            )
        }

        assertFalse(ok)
        assertTrue(file.exists())
        assertEquals(1, persistence.countAttachmentPathReferences(path))
        assertEquals(1, store.recent("peer-a").size)
        assertTrue(store.recent("peer-b").isEmpty())
    }

    @Test
    fun dbInsertSuccess_survivesPersistenceRestart() {
        val bytes = "durable".toByteArray(Charsets.UTF_8)
        val sha = AttachmentChunker.sha256Hex(bytes)
        val attachmentId = MessageAttachmentStorage.newAttachmentId()
        val file = storage.commitVerified(attachmentId, "durable.txt", bytes, sha)
        assertNotNull(file)

        val ok = InboundAttachmentPersistence.persistAfterCommit(
            attachmentLocalPath = file!!.absolutePath,
            storage = storage,
            persistence = persistence
        ) {
            store.append(
                inboundAttachmentRecord("peer-restart", file.absolutePath, file.length())
            )
        }
        assertTrue(ok)

        persistence.close()
        val reopened = MessagePersistence(context)
        val recent = reopened.recentDirect("peer-restart")
        assertEquals(1, recent.size)
        assertNotNull(recent.single().attachment)
        assertEquals(file.absolutePath, recent.single().attachment?.localPath)
        assertTrue(storage.fileForPath(file.absolutePath) != null)
        assertEquals(bytes.size.toLong(), storage.fileForPath(file.absolutePath)!!.length())
    }

    private fun inboundAttachmentRecord(
        endpointKey: String,
        path: String,
        sizeBytes: Long,
        timestampMs: Long = System.currentTimeMillis()
    ) = EndpointTextRecord(
        endpointKey = endpointKey,
        text = "",
        timestampMs = timestampMs,
        direction = EndpointTextDirection.INBOUND,
        attachment = MessageAttachmentInfo(
            kind = AttachmentKind.FILE,
            mimeType = "application/octet-stream",
            fileName = "test.bin",
            localPath = path,
            sizeBytes = sizeBytes
        )
    )
}
