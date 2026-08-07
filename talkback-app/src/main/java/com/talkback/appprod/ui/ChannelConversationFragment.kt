package com.talkback.appprod.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import com.talkback.appprod.endpointtext.ChannelTextRecord
import com.talkback.appprod.endpointtext.EndpointTextDirection
import kotlinx.coroutines.launch

class ChannelConversationFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    private var channelId: String = ""
    private var channelLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelId = requireArguments().getString(ARG_CHANNEL_ID).orEmpty()
        channelLabel = requireArguments().getString(ARG_CHANNEL_LABEL).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_conversation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.txtConversationTitle).text = channelLabel
        view.findViewById<View>(R.id.btnConversationCall).isVisible = false
        view.findViewById<View>(R.id.txtConversationOnline).isVisible = false
        view.findViewById<View>(R.id.btnConversationBack).setOnClickListener {
            (activity as? MainActivity)?.dismissConversation()
        }
        view.findViewById<View>(R.id.btnConversationMore).setOnClickListener {
            Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }

        val subtitle = view.findViewById<TextView>(R.id.txtConversationSubtitle)
        val empty = view.findViewById<View>(R.id.conversationEmptyState)
        val scroll = view.findViewById<ScrollView>(R.id.scrollConversation)
        val container = view.findViewById<LinearLayout>(R.id.containerConversationMessages)
        val input = view.findViewById<EditText>(R.id.editConversationInput)
        val send = view.findViewById<TextView>(R.id.btnConversationSend)

        fun renderMessages(messages: List<ChannelTextRecord>) {
            val hasMessages = messages.isNotEmpty()
            empty.isVisible = !hasMessages
            scroll.isVisible = hasMessages
            container.removeAllViews()
            if (!hasMessages) return
            val inflater = LayoutInflater.from(requireContext())
            messages.forEach { record ->
                container.addView(bindMessageRow(inflater, container, record))
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect {
                    val (total, online) = viewModel.channelMemberStats()
                    subtitle.text = getString(R.string.channel_conversation_members, total, online)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.conversationUi.collect {
                    renderMessages(viewModel.channelMessages(channelId))
                }
            }
        }

        fun submitMessage() {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            viewModel.syncServiceState()
            when (val err = viewModel.sendChannelText(text)) {
                null -> input.text?.clear()
                "SERVICE_STOPPED" -> toast(R.string.service_not_running)
                "NO_MEMBERS" -> toast(R.string.channel_text_no_members)
                "UNREACHABLE" -> toast(R.string.endpoint_text_unreachable)
                "TEXT_TOO_LONG" -> toast(R.string.endpoint_text_too_long)
                "SEND_FAILED" -> toast(R.string.endpoint_text_send_failed)
                else -> toast(R.string.endpoint_text_failed)
            }
        }

        send.setOnClickListener { submitMessage() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitMessage()
                true
            } else {
                false
            }
        }
        input.doAfterTextChanged {
            send.isEnabled = !it.isNullOrBlank()
        }
        send.isEnabled = false
    }

    override fun onStart() {
        super.onStart()
        viewModel.setOpenChannelConversation(channelId)
    }

    override fun onStop() {
        viewModel.setOpenChannelConversation(null)
        super.onStop()
    }

    private fun bindMessageRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        record: ChannelTextRecord
    ): View {
        val row = inflater.inflate(R.layout.item_conversation_message, parent, false)
        val bubble = row.findViewById<LinearLayout>(R.id.bubbleRoot)
        val outbound = record.direction == EndpointTextDirection.OUTBOUND
        val params = bubble.layoutParams as FrameLayout.LayoutParams
        if (outbound) {
            params.gravity = Gravity.END
            bubble.setBackgroundResource(R.drawable.bg_message_bubble_outbound)
            row.findViewById<TextView>(R.id.txtMessageSender).text =
                getString(R.string.conversation_sender_you)
        } else {
            params.gravity = Gravity.START
            bubble.setBackgroundResource(R.drawable.bg_message_bubble_inbound)
            row.findViewById<TextView>(R.id.txtMessageSender).text = record.senderLabel
        }
        bubble.layoutParams = params
        row.findViewById<TextView>(R.id.txtMessageTime).text =
            ConversationTimeFormat.formatMessageTime(record.timestampMs)
        row.findViewById<TextView>(R.id.txtMessageBody).text = record.text
        return row
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG_CHANNEL_CONVERSATION = "channel_conversation"
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_LABEL = "channelLabel"

        fun newInstance(channelId: String, channelLabel: String): ChannelConversationFragment {
            return ChannelConversationFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHANNEL_ID, channelId)
                    putString(ARG_CHANNEL_LABEL, channelLabel)
                }
            }
        }
    }
}
