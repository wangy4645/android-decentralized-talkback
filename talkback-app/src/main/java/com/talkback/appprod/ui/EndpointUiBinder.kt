package com.talkback.appprod.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.talkback.appprod.R

object EndpointUiBinder {
    fun bindRow(
        row: View,
        item: EndpointUiItem,
        keyView: TextView,
        statusView: TextView,
        dotView: View,
        waveformView: ImageView,
        signalBarsView: ImageView
    ) {
        val ctx = row.context
        keyView.text = item.displayLabel
        statusView.text = when (item.status) {
            EndpointStatus.SPEAKING -> ctx.getString(R.string.status_speaking)
            EndpointStatus.ONLINE -> ctx.getString(R.string.status_online)
            EndpointStatus.OFFLINE -> ctx.getString(R.string.status_offline)
            EndpointStatus.INVITING -> ctx.getString(R.string.status_inviting)
            EndpointStatus.CONNECTING -> ctx.getString(R.string.status_connecting)
            EndpointStatus.RECONNECTING -> ctx.getString(R.string.status_reconnecting)
            EndpointStatus.EXPIRED -> ctx.getString(R.string.status_expired)
        }

        val isSpeaking = item.status == EndpointStatus.SPEAKING
        val isOffline = item.status == EndpointStatus.OFFLINE
        val isReconnecting = item.status == EndpointStatus.RECONNECTING
        val isMuted = item.status == EndpointStatus.EXPIRED || item.status == EndpointStatus.INVITING
        waveformView.isVisible = isSpeaking
        signalBarsView.isVisible = !isSpeaking && !isOffline && !isMuted && !isReconnecting

        val dotDrawable = when (item.status) {
            EndpointStatus.SPEAKING -> R.drawable.dot_speaking
            EndpointStatus.ONLINE -> R.drawable.dot_online
            EndpointStatus.OFFLINE -> R.drawable.dot_offline
            EndpointStatus.INVITING -> R.drawable.dot_offline
            EndpointStatus.CONNECTING -> R.drawable.dot_meeting_connecting
            EndpointStatus.RECONNECTING -> R.drawable.dot_reconnecting
            EndpointStatus.EXPIRED -> R.drawable.dot_offline
        }
        dotView.background = ContextCompat.getDrawable(ctx, dotDrawable)

        row.setBackgroundResource(
            when {
                isSpeaking -> R.drawable.bg_endpoint_item_speaking
                item.isLocal -> R.drawable.bg_endpoint_item_local
                else -> R.drawable.bg_endpoint_item
            }
        )

        val statusColor = when (item.status) {
            EndpointStatus.SPEAKING -> R.color.tb_primary
            EndpointStatus.ONLINE -> if (item.isLocal) R.color.tb_primary else R.color.tb_success
            EndpointStatus.CONNECTING -> R.color.tb_meeting_connecting
            EndpointStatus.RECONNECTING -> R.color.tb_text_muted
            EndpointStatus.INVITING -> R.color.tb_text_muted
            EndpointStatus.EXPIRED -> R.color.tb_text_muted
            EndpointStatus.OFFLINE -> R.color.tb_text_muted
        }
        statusView.setTextColor(ContextCompat.getColor(ctx, statusColor))

        keyView.alpha = if (isOffline || isMuted || isReconnecting) 0.55f else 1f
        signalBarsView.alpha = if (!isSpeaking && (isOffline || isMuted || isReconnecting)) 0.35f else 1f
    }
}
