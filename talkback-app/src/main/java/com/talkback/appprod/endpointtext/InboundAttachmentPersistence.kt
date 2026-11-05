package com.talkback.appprod.endpointtext

import com.talkback.core.util.TalkbackLog

/**
 * Ensures inbound attachment files are not left orphaned when SQLite insert fails
 * after [MessageAttachmentStorage.commitVerified].
 */
object InboundAttachmentPersistence {

    /**
     * Runs [insertMessage]; on failure rolls back [attachmentLocalPath] unless other rows still reference it.
     * @return true when the message row was persisted (or no persistence layer).
     */
    fun persistAfterCommit(
        attachmentLocalPath: String,
        storage: MessageAttachmentStorage,
        persistence: MessagePersistence?,
        insertMessage: () -> Boolean
    ): Boolean {
        if (insertMessage()) return true
        rollbackOrphanFile(attachmentLocalPath, storage, persistence)
        return false
    }

    fun rollbackOrphanFile(
        path: String,
        storage: MessageAttachmentStorage,
        persistence: MessagePersistence?
    ) {
        val logRef = MessageAttachmentStorage.logRefForLocalPath(path)
        if (persistence != null) {
            val result = persistence.rollbackAttachmentFileAfterInsertFailure(path, storage)
            when (result.outcome) {
                AttachmentInsertRollbackOutcome.KeptSharedReferences ->
                    TalkbackLog.w(
                        "MESSAGE_ATTACHMENT db_insert_failed keep_shared_file " +
                            "$logRef refs=${result.refCount}"
                    )
                AttachmentInsertRollbackOutcome.Deleted ->
                    TalkbackLog.w(
                        "MESSAGE_ATTACHMENT db_insert_failed rolled_back_file $logRef"
                    )
                AttachmentInsertRollbackOutcome.Missing ->
                    TalkbackLog.w(
                        "MESSAGE_ATTACHMENT db_insert_failed rollback_missing $logRef"
                    )
                AttachmentInsertRollbackOutcome.RejectedPath ->
                    TalkbackLog.w(
                        "MESSAGE_ATTACHMENT db_insert_failed rollback_rejected_path $logRef"
                    )
                AttachmentInsertRollbackOutcome.DeleteFailed ->
                    TalkbackLog.w(
                        "MESSAGE_ATTACHMENT db_insert_failed file_delete_failed $logRef " +
                            "detail=${result.deleteDiagnostic}"
                    )
            }
            return
        }
        val attempt = storage.tryDeleteFileAtPath(path)
        when (attempt.outcome) {
            MessageAttachmentStorage.DeleteOutcome.Deleted ->
                TalkbackLog.w("MESSAGE_ATTACHMENT db_insert_failed rolled_back_file $logRef")
            MessageAttachmentStorage.DeleteOutcome.Missing ->
                TalkbackLog.w("MESSAGE_ATTACHMENT db_insert_failed rollback_missing $logRef")
            MessageAttachmentStorage.DeleteOutcome.RejectedPath ->
                TalkbackLog.w("MESSAGE_ATTACHMENT db_insert_failed rollback_rejected_path $logRef")
            MessageAttachmentStorage.DeleteOutcome.DeleteFailed ->
                TalkbackLog.w(
                    "MESSAGE_ATTACHMENT db_insert_failed file_delete_failed $logRef " +
                        "detail=${attempt.diagnostic}"
                )
        }
    }
}

enum class AttachmentInsertRollbackOutcome {
    KeptSharedReferences,
    Deleted,
    Missing,
    RejectedPath,
    DeleteFailed
}

data class AttachmentInsertRollbackResult(
    val outcome: AttachmentInsertRollbackOutcome,
    val refCount: Int = 0,
    val deleteDiagnostic: String = ""
)
