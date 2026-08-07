package com.talkback.appprod.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.talkback.appprod.R

class MessageComposeBottomSheet : BottomSheetDialogFragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_message_compose, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val team = viewModel.teamDisplayName()
        val youSuffix = getString(R.string.you_suffix)
        val targets = viewModel.uiState.value.endpoints.filter {
            !it.isLocal &&
                it.status != EndpointStatus.OFFLINE &&
                !it.displayLabel.endsWith(youSuffix)
        }
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerComposeTargets)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = TargetAdapter(team, targets) { item ->
            setFragmentResult(
                REQUEST_COMPOSE_TARGET,
                bundleOf(
                    ARG_KEY to item.key,
                    ARG_LABEL to item.displayLabel
                )
            )
            dismiss()
        }
        view.findViewById<View>(R.id.btnComposeCancel).setOnClickListener { dismiss() }
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), R.string.message_compose_no_targets, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private class TargetAdapter(
        private val teamName: String,
        private val items: List<EndpointUiItem>,
        private val onClick: (EndpointUiItem) -> Unit
    ) : RecyclerView.Adapter<TargetAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message_compose_target, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], teamName, onClick)
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val strokePx =
                (1f * itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1)

            fun bind(item: EndpointUiItem, teamName: String, onClick: (EndpointUiItem) -> Unit) {
                val ctx = itemView.context
                val accent = ContextCompat.getColor(
                    ctx,
                    ConversationAccentPalette.accentResId(item.key)
                )
                itemView.findViewById<FrameLayout>(R.id.frameComposeAvatar).background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ConversationAccentPalette.fillColor(accent))
                        setStroke(strokePx, accent)
                    }
                itemView.findViewById<ImageView>(R.id.imgComposeAvatar).setColorFilter(accent)
                itemView.findViewById<TextView>(R.id.txtComposeTargetLabel).text = item.displayLabel
                itemView.findViewById<TextView>(R.id.txtComposeTargetTeam).text = teamName
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }

    companion object {
        const val REQUEST_COMPOSE_TARGET = "message_compose_target"
        const val ARG_KEY = "key"
        const val ARG_LABEL = "label"

        fun newInstance(): MessageComposeBottomSheet = MessageComposeBottomSheet()
    }
}
