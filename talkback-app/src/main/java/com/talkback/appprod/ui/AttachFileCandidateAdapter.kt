package com.talkback.appprod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.talkback.appprod.R

internal class AttachFileCandidateAdapter(
    private val items: List<AttachFileCandidate>,
    private val onPick: (AttachFileCandidate) -> Unit
) : RecyclerView.Adapter<AttachFileCandidateAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.txtAttachFileName)
        val meta: TextView = view.findViewById(R.id.txtAttachFileMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attach_file_candidate, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.displayName
        holder.meta.text = formatSize(item.sizeBytes)
        holder.itemView.setOnClickListener { onPick(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.1f MB", kb / 1024.0)
    }
}
