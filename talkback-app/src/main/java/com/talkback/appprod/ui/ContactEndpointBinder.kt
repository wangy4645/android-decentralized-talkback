package com.talkback.appprod.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.talkback.appprod.R

object ContactEndpointBinder {
    fun bind(row: View, item: EndpointUiItem, showDivider: Boolean, teamName: String? = null) {
        val team = row.findViewById<TextView>(R.id.txtEndpointTeam)
        val divider = row.findViewById<View>(R.id.dividerBottom)

        if (teamName.isNullOrBlank()) {
            team.visibility = View.GONE
        } else {
            team.visibility = View.VISIBLE
            team.text = teamName
        }

        EndpointUiBinder.bindRow(
            row = row,
            item = item,
            keyView = row.findViewById(R.id.txtEndpointKey),
            statusView = row.findViewById(R.id.txtEndpointStatus),
            dotView = row.findViewById(R.id.dotStatus),
            waveformView = row.findViewById(R.id.imgWaveform),
            signalBarsView = row.findViewById(R.id.imgSignalBars)
        )
        divider.isVisible = showDivider
    }
}
