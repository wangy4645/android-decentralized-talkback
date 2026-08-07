package com.talkback.appprod.endpointtext

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.talkback.appprod.R
import com.talkback.appprod.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inbound Endpoint Text alerts — sender only, no message body (ADR-0039 presentation).
 */
class EndpointTextInboundNotifier(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    var foregroundToastHandler: ((ForegroundBatch) -> Unit)? = null

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val pendingSenders = LinkedHashMap<String, SenderHint>()
    private val foregroundBatch = LinkedHashMap<String, SenderHint>()
    private var foregroundToastJob: Job? = null

    init {
        ensureChannel()
    }

    fun onInbound(fromKey: String, fromLabel: String, teamName: String) {
        val hint = SenderHint(fromKey, fromLabel, teamName, isChannel = false)
        if (isAppInForeground()) {
            scheduleForegroundToast(hint)
        } else {
            synchronized(pendingSenders) {
                pendingSenders[fromKey] = hint
            }
            postNotification()
        }
    }

    fun onChannelInbound(channelId: String, channelName: String) {
        val hint = SenderHint("c:$channelId", channelName, channelName, isChannel = true)
        if (isAppInForeground()) {
            scheduleForegroundToast(hint)
        } else {
            synchronized(pendingSenders) {
                pendingSenders[hint.key] = hint
            }
            postNotification()
        }
    }

    fun clearNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
        synchronized(pendingSenders) { pendingSenders.clear() }
    }

    private fun scheduleForegroundToast(hint: SenderHint) {
        synchronized(foregroundBatch) {
            foregroundBatch[hint.key] = hint
        }
        foregroundToastJob?.cancel()
        foregroundToastJob = scope.launch {
            delay(FOREGROUND_BATCH_MS)
            val batch = synchronized(foregroundBatch) {
                val senders = foregroundBatch.values.toList()
                foregroundBatch.clear()
                ForegroundBatch(senders)
            }
            if (batch.senders.isNotEmpty()) {
                foregroundToastHandler?.invoke(batch)
            }
        }
    }

    private fun postNotification() {
        val senders = synchronized(pendingSenders) { pendingSenders.values.toList() }
        if (senders.isEmpty()) return

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (senders.size == 1 && senders[0].isChannel) {
                putExtra(MainActivity.EXTRA_OPEN_CHANNEL_CONVERSATION, true)
            } else if (senders.size == 1) {
                putExtra(MainActivity.EXTRA_OPEN_CONVERSATION_KEY, senders[0].key)
                putExtra(MainActivity.EXTRA_OPEN_CONVERSATION_LABEL, senders[0].label)
            } else {
                putExtra(MainActivity.EXTRA_OPEN_MESSAGES_TAB, true)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title: String
        val text: String
        if (senders.size == 1) {
            val sender = senders[0]
            title = appContext.getString(R.string.message_notification_title)
            text = if (sender.isChannel) {
                appContext.getString(R.string.message_notification_channel_body, sender.label)
            } else {
                appContext.getString(
                    R.string.message_notification_direct_body,
                    sender.label
                )
            }
        } else {
            title = appContext.getString(R.string.message_notification_multi_title, senders.size)
            text = appContext.getString(
                R.string.message_notification_multi_body,
                senders.joinToString(", ") { it.label }
            )
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_talking)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.message_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    data class SenderHint(
        val key: String,
        val label: String,
        val teamName: String,
        val isChannel: Boolean = false
    )

    data class ForegroundBatch(val senders: List<SenderHint>)

    companion object {
        private const val CHANNEL_ID = "endpoint_text_inbound"
        private const val NOTIFICATION_ID = 4102
        private const val FOREGROUND_BATCH_MS = 700L
    }
}
