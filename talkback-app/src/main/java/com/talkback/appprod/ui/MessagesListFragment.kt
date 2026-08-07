package com.talkback.appprod.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.talkback.appprod.R
import com.talkback.appprod.endpointtext.ConversationSummary
import kotlinx.coroutines.launch

class MessagesListFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }
    private lateinit var adapter: ConversationSummaryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(
            MessageComposeBottomSheet.REQUEST_COMPOSE_TARGET,
            this
        ) { _, bundle ->
            val remoteKey = bundle.getString(MessageComposeBottomSheet.ARG_KEY).orEmpty()
            val remoteLabel = bundle.getString(MessageComposeBottomSheet.ARG_LABEL).orEmpty()
            if (remoteKey.isBlank()) return@setFragmentResultListener
            (activity as? MainActivity)?.showConversation(remoteKey, remoteLabel)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_messages_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val empty = view.findViewById<View>(R.id.messagesEmptyState)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerMessages)
        val fab = view.findViewById<FloatingActionButton>(R.id.fabComposeMessage)
        val channelHost = view.findViewById<FrameLayout>(R.id.rowChannelConversationHost)
        val channelRow = layoutInflater.inflate(
            R.layout.item_channel_conversation_summary,
            channelHost,
            false
        )
        channelHost.addView(channelRow)

        adapter = ConversationSummaryAdapter(
            teamName = { viewModel.teamDisplayName() },
            labelForKey = { viewModel.endpointLabelForKey(it) },
            isOnline = { key ->
                viewModel.uiState.value.endpoints
                    .firstOrNull { it.key == key }
                    ?.status != EndpointStatus.OFFLINE
            },
            onClick = { summary ->
                (activity as? MainActivity)?.showConversation(
                    remoteKey = summary.endpointKey,
                    remoteLabel = viewModel.endpointLabelForKey(summary.endpointKey)
                )
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        fab.setOnClickListener {
            viewModel.syncServiceState()
            if (!viewModel.isServiceReady()) {
                Toast.makeText(requireContext(), R.string.service_not_running, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MessageComposeBottomSheet.newInstance()
                .show(parentFragmentManager, "message_compose")
        }

        channelRow.setOnClickListener {
            val state = viewModel.conversationUi.value
            (activity as? MainActivity)?.showChannelConversation(
                state.channelId,
                state.channelDisplayName.ifBlank { getString(R.string.messages_section_channels) }
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.conversationUi.collect { state ->
                    bindChannelRow(channelRow, state)
                    adapter.submit(state.summaries)
                    val hasItems = state.summaries.isNotEmpty()
                    empty.isVisible = !hasItems
                    recycler.isVisible = hasItems
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect {
                    adapter.notifyOnlineChanged()
                }
            }
        }
    }

    private fun bindChannelRow(row: View, state: ConversationListUiState) {
        row.findViewById<TextView>(R.id.txtChannelLabel).text =
            state.channelDisplayName.ifBlank { getString(R.string.messages_section_channels) }
        val preview = state.channelSummary?.lastMessage
        row.findViewById<TextView>(R.id.txtChannelPreview).text =
            preview?.takeIf { it.isNotBlank() }
                ?: getString(R.string.channel_message_preview_empty)
        val timeView = row.findViewById<TextView>(R.id.txtChannelTime)
        val ts = state.channelSummary?.lastTimestampMs
        if (ts != null && ts > 0L) {
            timeView.isVisible = true
            timeView.text = ConversationTimeFormat.formatListTime(requireContext(), ts)
        } else {
            timeView.isVisible = false
        }
        val unread = row.findViewById<TextView>(R.id.txtChannelUnread)
        if (state.channelUnread > 0) {
            unread.isVisible = true
            unread.text = state.channelUnread.toString()
        } else {
            unread.isVisible = false
        }
    }

    private class ConversationSummaryAdapter(
        private val teamName: () -> String,
        private val labelForKey: (String) -> String,
        private val isOnline: (String) -> Boolean,
        private val onClick: (ConversationSummary) -> Unit
    ) : RecyclerView.Adapter<ConversationSummaryAdapter.Holder>() {

        private var items: List<ConversationSummary> = emptyList()

        fun submit(summaries: List<ConversationSummary>) {
            items = summaries
            notifyDataSetChanged()
        }

        fun notifyOnlineChanged() {
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation_summary, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtAvatar = itemView.findViewById<TextView>(R.id.txtConversationAvatar)
            private val dotOnline = itemView.findViewById<View>(R.id.dotOnline)
            private val txtLabel = itemView.findViewById<TextView>(R.id.txtConversationLabel)
            private val txtTeam = itemView.findViewById<TextView>(R.id.txtConversationTeam)
            private val txtPreview = itemView.findViewById<TextView>(R.id.txtConversationPreview)
            private val txtTime = itemView.findViewById<TextView>(R.id.txtConversationTime)
            private val txtUnread = itemView.findViewById<TextView>(R.id.txtConversationUnread)
            private val strokePx = (1f * itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1)

            fun bind(summary: ConversationSummary) {
                val ctx = itemView.context
                val label = labelForKey(summary.endpointKey)
                val online = isOnline(summary.endpointKey)
                val baseAccent = ContextCompat.getColor(
                    ctx,
                    ConversationAccentPalette.accentResId(summary.endpointKey)
                )
                val accent = if (online) {
                    baseAccent
                } else {
                    ConversationAccentPalette.offlineAccent(baseAccent)
                }
                txtAvatar.text = avatarInitials(label)
                txtAvatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ConversationAccentPalette.fillColor(accent))
                    setStroke(strokePx, accent)
                }
                txtAvatar.setTextColor(accent)
                dotOnline.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
                dotOnline.isVisible = online
                txtLabel.text = label
                txtTeam.text = teamName()
                txtPreview.text = summary.lastMessage
                txtTime.text = ConversationTimeFormat.formatListTime(ctx, summary.lastTimestampMs)
                if (summary.unreadCount > 0) {
                    txtUnread.isVisible = true
                    txtUnread.text = summary.unreadCount.toString()
                    val badge = ContextCompat.getDrawable(ctx, R.drawable.bg_unread_badge)?.mutate()
                    if (badge != null) {
                        DrawableCompat.setTint(badge, baseAccent)
                        txtUnread.background = badge
                    }
                } else {
                    txtUnread.isVisible = false
                }
                itemView.setOnClickListener { onClick(summary) }
            }

            private fun avatarInitials(label: String): String {
                val cleaned = label.removeSuffix(" (You)").trim()
                return cleaned.take(3).ifBlank { "?" }
            }
        }
    }
}
