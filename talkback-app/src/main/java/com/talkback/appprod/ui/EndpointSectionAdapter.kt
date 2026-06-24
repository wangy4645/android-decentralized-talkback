package com.talkback.appprod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.talkback.appprod.R

sealed class ContactListRow {
    data class Header(val title: String) : ContactListRow()
    data class Endpoint(val item: EndpointUiItem) : ContactListRow()
}

class EndpointSectionAdapter : ListAdapter<ContactListRow, RecyclerView.ViewHolder>(Diff) {
    object Diff : DiffUtil.ItemCallback<ContactListRow>() {
        override fun areItemsTheSame(oldItem: ContactListRow, newItem: ContactListRow): Boolean {
            return when {
                oldItem is ContactListRow.Header && newItem is ContactListRow.Header ->
                    oldItem.title == newItem.title
                oldItem is ContactListRow.Endpoint && newItem is ContactListRow.Endpoint ->
                    oldItem.item.key == newItem.item.key
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ContactListRow, newItem: ContactListRow): Boolean =
            oldItem == newItem
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ContactListRow.Header -> VIEW_HEADER
        is ContactListRow.Endpoint -> VIEW_ENDPOINT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_HEADER -> HeaderVH(
                inflater.inflate(R.layout.item_contact_section_header, parent, false)
            )
            else -> EndpointAdapter.VH(
                inflater.inflate(R.layout.item_endpoint, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ContactListRow.Header -> (holder as HeaderVH).title.text = row.title
            is ContactListRow.Endpoint -> EndpointAdapter.bindHolder(holder as EndpointAdapter.VH, row.item)
        }
    }

    private class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtSectionTitle)
    }

    companion object {
        private const val VIEW_HEADER = 0
        private const val VIEW_ENDPOINT = 1

        fun buildRows(
            endpoints: List<EndpointUiItem>,
            onlineHeader: String,
            offlineHeader: String
        ): List<ContactListRow> {
            val online = endpoints.filter { it.status != EndpointStatus.OFFLINE }
            val offline = endpoints.filter { it.status == EndpointStatus.OFFLINE }
            val rows = mutableListOf<ContactListRow>()
            if (online.isNotEmpty()) {
                rows += ContactListRow.Header(onlineHeader)
                online.forEach { rows += ContactListRow.Endpoint(it) }
            }
            if (offline.isNotEmpty()) {
                rows += ContactListRow.Header(offlineHeader)
                offline.forEach { rows += ContactListRow.Endpoint(it) }
            }
            if (rows.isEmpty()) {
                rows += ContactListRow.Header(onlineHeader)
            }
            return rows
        }
    }
}
