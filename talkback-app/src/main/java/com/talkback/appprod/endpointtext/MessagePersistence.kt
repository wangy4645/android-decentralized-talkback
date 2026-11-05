package com.talkback.appprod.endpointtext

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.talkback.core.endpointattachment.AttachmentKind

/**
 * SQLite persistence for Tactical Message threads (direct + channel).
 * Survives process death; attachment files live under [MessageAttachmentStorage].
 */
class MessagePersistence(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE direct_messages (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              endpoint_key TEXT NOT NULL,
              text TEXT NOT NULL,
              timestamp_ms INTEGER NOT NULL,
              direction INTEGER NOT NULL,
              attachment_kind TEXT,
              attachment_mime TEXT,
              attachment_file_name TEXT,
              attachment_local_path TEXT,
              attachment_size INTEGER,
              is_read INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_direct_endpoint_ts ON direct_messages(endpoint_key, timestamp_ms)"
        )
        db.execSQL(
            """
            CREATE TABLE channel_messages (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              channel_id TEXT NOT NULL,
              text TEXT NOT NULL,
              timestamp_ms INTEGER NOT NULL,
              direction INTEGER NOT NULL,
              sender_key TEXT NOT NULL,
              sender_label TEXT NOT NULL,
              attachment_kind TEXT,
              attachment_mime TEXT,
              attachment_file_name TEXT,
              attachment_local_path TEXT,
              attachment_size INTEGER,
              is_read INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_channel_ts ON channel_messages(channel_id, timestamp_ms)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    @Synchronized
    fun insertDirect(record: EndpointTextRecord, markRead: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("endpoint_key", record.endpointKey)
                put("text", record.text)
                put("timestamp_ms", record.timestampMs)
                put(
                    "direction",
                    if (record.direction == EndpointTextDirection.OUTBOUND) 1 else 0
                )
                put("attachment_kind", record.attachment?.kind?.name)
                put("attachment_mime", record.attachment?.mimeType)
                put("attachment_file_name", record.attachment?.fileName)
                put("attachment_local_path", record.attachment?.localPath)
                put("attachment_size", record.attachment?.sizeBytes)
                put(
                    "is_read",
                    if (record.direction == EndpointTextDirection.OUTBOUND || markRead) 1 else 0
                )
            }
            db.insert("direct_messages", null, values)
            trimDirectThread(db, record.endpointKey)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun insertChannel(record: ChannelTextRecord, markRead: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("channel_id", record.channelId)
                put("text", record.text)
                put("timestamp_ms", record.timestampMs)
                put(
                    "direction",
                    if (record.direction == EndpointTextDirection.OUTBOUND) 1 else 0
                )
                put("sender_key", record.senderKey)
                put("sender_label", record.senderLabel)
                put("attachment_kind", record.attachment?.kind?.name)
                put("attachment_mime", record.attachment?.mimeType)
                put("attachment_file_name", record.attachment?.fileName)
                put("attachment_local_path", record.attachment?.localPath)
                put("attachment_size", record.attachment?.sizeBytes)
                put(
                    "is_read",
                    if (record.direction == EndpointTextDirection.OUTBOUND || markRead) 1 else 0
                )
            }
            db.insert("channel_messages", null, values)
            trimChannelThread(db, record.channelId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun recentDirect(endpointKey: String, limit: Int = MAX_ROWS_PER_THREAD): List<EndpointTextRecord> {
        if (endpointKey.isBlank()) return emptyList()
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT endpoint_key, text, timestamp_ms, direction,
                   attachment_kind, attachment_mime, attachment_file_name, attachment_local_path, attachment_size
            FROM direct_messages
            WHERE endpoint_key = ?
            ORDER BY timestamp_ms ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(endpointKey, limit.toString())
        )
        cursor.use {
            val out = ArrayList<EndpointTextRecord>()
            while (it.moveToNext()) {
                out.add(it.toDirectRecord())
            }
            return out
        }
    }

    @Synchronized
    fun recentChannel(channelId: String, limit: Int = MAX_ROWS_PER_THREAD): List<ChannelTextRecord> {
        if (channelId.isBlank()) return emptyList()
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT channel_id, text, timestamp_ms, direction, sender_key, sender_label,
                   attachment_kind, attachment_mime, attachment_file_name, attachment_local_path, attachment_size
            FROM channel_messages
            WHERE channel_id = ?
            ORDER BY timestamp_ms ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(channelId, limit.toString())
        )
        cursor.use {
            val out = ArrayList<ChannelTextRecord>()
            while (it.moveToNext()) {
                out.add(it.toChannelRecord())
            }
            return out
        }
    }

    @Synchronized
    fun directSummaries(): List<ConversationSummary> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT d.endpoint_key, d.text, d.timestamp_ms, d.direction, d.attachment_kind,
                   (SELECT COUNT(*) FROM direct_messages u
                    WHERE u.endpoint_key = d.endpoint_key AND u.direction = 0 AND u.is_read = 0) AS unread
            FROM direct_messages d
            INNER JOIN (
              SELECT endpoint_key, MAX(timestamp_ms) AS max_ts
              FROM direct_messages GROUP BY endpoint_key
            ) last ON last.endpoint_key = d.endpoint_key AND last.max_ts = d.timestamp_ms
            ORDER BY d.timestamp_ms DESC
            """.trimIndent(),
            null
        )
        cursor.use {
            val out = ArrayList<ConversationSummary>()
            while (it.moveToNext()) {
                val direction =
                    if (it.getInt(3) == 1) EndpointTextDirection.OUTBOUND else EndpointTextDirection.INBOUND
                out.add(
                    ConversationSummary(
                        endpointKey = it.getString(0),
                        lastMessage = previewLine(it.getString(1), it.getString(4)),
                        lastTimestampMs = it.getLong(2),
                        lastDirection = direction,
                        unreadCount = it.getInt(5)
                    )
                )
            }
            return out
        }
    }

    @Synchronized
    fun channelSummary(channelId: String): ChannelConversationSummary? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT c.channel_id, c.text, c.timestamp_ms, c.sender_label, c.attachment_kind,
                   (SELECT COUNT(*) FROM channel_messages u
                    WHERE u.channel_id = c.channel_id AND u.direction = 0 AND u.is_read = 0) AS unread
            FROM channel_messages c
            WHERE c.channel_id = ?
            ORDER BY c.timestamp_ms DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(channelId)
        )
        cursor.use {
            if (!it.moveToNext()) return null
            return ChannelConversationSummary(
                channelId = it.getString(0),
                lastMessage = previewLine(it.getString(1), it.getString(4)),
                lastTimestampMs = it.getLong(2),
                lastSenderLabel = it.getString(3),
                unreadCount = it.getInt(5)
            )
        }
    }

    @Synchronized
    fun totalDirectUnread(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM direct_messages WHERE direction = 0 AND is_read = 0",
            null
        )
        cursor.use {
            return if (it.moveToNext()) it.getInt(0) else 0
        }
    }

    @Synchronized
    fun channelUnread(channelId: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM channel_messages WHERE channel_id = ? AND direction = 0 AND is_read = 0",
            arrayOf(channelId)
        )
        cursor.use {
            return if (it.moveToNext()) it.getInt(0) else 0
        }
    }

    @Synchronized
    fun totalChannelUnread(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM channel_messages WHERE direction = 0 AND is_read = 0",
            null
        )
        cursor.use {
            return if (it.moveToNext()) it.getInt(0) else 0
        }
    }

    @Synchronized
    fun markDirectRead(endpointKey: String) {
        if (endpointKey.isBlank()) return
        writableDatabase.execSQL(
            "UPDATE direct_messages SET is_read = 1 WHERE endpoint_key = ? AND direction = 0",
            arrayOf(endpointKey)
        )
    }

    @Synchronized
    fun markChannelRead(channelId: String) {
        if (channelId.isBlank()) return
        writableDatabase.execSQL(
            "UPDATE channel_messages SET is_read = 1 WHERE channel_id = ? AND direction = 0",
            arrayOf(channelId)
        )
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("direct_messages", null, null)
        writableDatabase.delete("channel_messages", null, null)
    }

    private fun trimDirectThread(db: SQLiteDatabase, endpointKey: String) {
        db.execSQL(
            """
            DELETE FROM direct_messages
            WHERE endpoint_key = ? AND id NOT IN (
              SELECT id FROM direct_messages
              WHERE endpoint_key = ?
              ORDER BY timestamp_ms DESC
              LIMIT ?
            )
            """.trimIndent(),
            arrayOf(endpointKey, endpointKey, MAX_ROWS_PER_THREAD.toString())
        )
    }

    private fun trimChannelThread(db: SQLiteDatabase, channelId: String) {
        db.execSQL(
            """
            DELETE FROM channel_messages
            WHERE channel_id = ? AND id NOT IN (
              SELECT id FROM channel_messages
              WHERE channel_id = ?
              ORDER BY timestamp_ms DESC
              LIMIT ?
            )
            """.trimIndent(),
            arrayOf(channelId, channelId, MAX_ROWS_PER_THREAD.toString())
        )
    }

    private fun previewLine(text: String, attachmentKind: String?): String {
        if (text.isNotBlank()) return text
        return when (attachmentKind) {
            AttachmentKind.IMAGE.name -> "[Image]"
            AttachmentKind.VOICE.name -> "[Voice]"
            AttachmentKind.FILE.name -> "[File]"
            else -> text
        }
    }

    private fun android.database.Cursor.toDirectRecord(): EndpointTextRecord {
        val attachment = readAttachment(this)
        return EndpointTextRecord(
            endpointKey = getString(getColumnIndexOrThrow("endpoint_key")),
            text = getString(getColumnIndexOrThrow("text")),
            timestampMs = getLong(getColumnIndexOrThrow("timestamp_ms")),
            direction = if (getInt(getColumnIndexOrThrow("direction")) == 1) {
                EndpointTextDirection.OUTBOUND
            } else {
                EndpointTextDirection.INBOUND
            },
            attachment = attachment
        )
    }

    private fun android.database.Cursor.toChannelRecord(): ChannelTextRecord {
        return ChannelTextRecord(
            channelId = getString(getColumnIndexOrThrow("channel_id")),
            text = getString(getColumnIndexOrThrow("text")),
            timestampMs = getLong(getColumnIndexOrThrow("timestamp_ms")),
            direction = if (getInt(getColumnIndexOrThrow("direction")) == 1) {
                EndpointTextDirection.OUTBOUND
            } else {
                EndpointTextDirection.INBOUND
            },
            senderKey = getString(getColumnIndexOrThrow("sender_key")),
            senderLabel = getString(getColumnIndexOrThrow("sender_label")),
            attachment = readAttachment(this)
        )
    }

    private fun readAttachment(c: android.database.Cursor): MessageAttachmentInfo? {
        val kindRaw = c.getString(c.getColumnIndexOrThrow("attachment_kind")) ?: return null
        val kind = AttachmentKind.fromWire(kindRaw) ?: return null
        val path = c.getString(c.getColumnIndexOrThrow("attachment_local_path")) ?: return null
        if (path.isBlank()) return null
        return MessageAttachmentInfo(
            kind = kind,
            mimeType = c.getString(c.getColumnIndexOrThrow("attachment_mime")).orEmpty(),
            fileName = c.getString(c.getColumnIndexOrThrow("attachment_file_name")).orEmpty(),
            localPath = path,
            sizeBytes = c.getLong(c.getColumnIndexOrThrow("attachment_size"))
        )
    }

    companion object {
        private const val DB_NAME = "tactical_messages.db"
        private const val DB_VERSION = 1
        const val MAX_ROWS_PER_THREAD = 500
    }
}
