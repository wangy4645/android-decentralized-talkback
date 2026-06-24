package com.talkback.appprod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.talkback.appprod.R

class EndpointAdapter : ListAdapter<EndpointUiItem, EndpointAdapter.VH>(Diff) {
    object Diff : DiffUtil.ItemCallback<EndpointUiItem>() {
        override fun areItemsTheSame(oldItem: EndpointUiItem, newItem: EndpointUiItem): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: EndpointUiItem, newItem: EndpointUiItem): Boolean =
            oldItem == newItem
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.dotStatus)
        val key: TextView = view.findViewById(R.id.txtEndpointKey)
        val status: TextView = view.findViewById(R.id.txtEndpointStatus)
        val signalBars: ImageView = view.findViewById(R.id.imgSignalBars)
        val waveform: ImageView = view.findViewById(R.id.imgWaveform)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_endpoint, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        bindHolder(holder, getItem(position))
    }

    companion object {
        fun bindHolder(holder: VH, item: EndpointUiItem) {
            EndpointUiBinder.bindRow(
                row = holder.itemView,
                item = item,
                keyView = holder.key,
                statusView = holder.status,
                dotView = holder.dot,
                waveformView = holder.waveform,
                signalBarsView = holder.signalBars
            )
        }
    }
}
