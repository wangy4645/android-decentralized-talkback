package com.talkback.appprod.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import com.talkback.appprod.endpointtext.EndpointTextDirection
import com.talkback.appprod.endpointtext.EndpointTextRecord
import android.net.Uri
import com.talkback.core.endpointattachment.AttachmentKind
import com.talkback.appprod.ui.call.CallLaunchContext
import com.talkback.appprod.ui.call.CallReturnTarget
import com.talkback.appprod.ui.call.CallSource
import com.talkback.appprod.ui.call.PrivateCallNavigator
import kotlinx.coroutines.launch

class ConversationFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    private var remoteKey: String = ""
    private var remoteLabel: String = ""
    private lateinit var attachmentActions: ConversationAttachmentActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remoteKey = requireArguments().getString(ARG_KEY).orEmpty()
        remoteLabel = requireArguments().getString(ARG_LABEL).orEmpty()
        attachmentActions =
            ConversationAttachmentActions(
                fragment = this,
                onBytesReady = { bytes, kind, mimeType, fileName ->
                    sendAttachment(bytes, kind, mimeType, fileName)
                },
                onLargeUriReady = { uri, kind, mimeType, fileName, sizeBytes ->
                    sendLargeAttachment(uri, kind, mimeType, fileName, sizeBytes)
                },
            )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_conversation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.txtConversationTitle).text = remoteLabel
        view.findViewById<TextView>(R.id.txtConversationSubtitle).text = viewModel.teamDisplayName()
        view.findViewById<View>(R.id.btnConversationBack).setOnClickListener {
            (activity as? MainActivity)?.dismissConversation()
        }
        view.findViewById<View>(R.id.btnConversationMore).setOnClickListener {
            Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnConversationCall).setOnClickListener {
            startPrivateCallFromConversation()
        }

        val onlinePill = view.findViewById<TextView>(R.id.txtConversationOnline)
        val callButton = view.findViewById<ImageView>(R.id.btnConversationCall)
        val empty = view.findViewById<View>(R.id.conversationEmptyState)
        val scroll = view.findViewById<ScrollView>(R.id.scrollConversation)
        val container = view.findViewById<LinearLayout>(R.id.containerConversationMessages)
        val input = view.findViewById<EditText>(R.id.editConversationInput)
        val send = view.findViewById<TextView>(R.id.btnConversationSend)
        ConversationMorePanelBinder.bind(view, attachmentActions)
        ConversationVoiceInputBinder.bind(view, attachmentActions)
        ConversationAttachPanelBack.install(this, view)

        fun renderMessages(messages: List<EndpointTextRecord>) {
            val outbox = viewModel.bulkOutboxFor(remoteKey).map { it.toRecord() }
            val merged = messages + outbox
            val hasMessages = merged.isNotEmpty()
            empty.isVisible = !hasMessages
            scroll.isVisible = hasMessages
            container.removeAllViews()
            if (!hasMessages) return
            val inflater = LayoutInflater.from(requireContext())
            merged.forEach { record ->
                container.addView(bindMessageRow(inflater, container, record))
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val online = state.endpoints
                        .firstOrNull { it.key == remoteKey }
                        ?.let { it.status != EndpointStatus.OFFLINE }
                        ?: false
                    onlinePill.isVisible = true
                    onlinePill.text = getString(
                        if (online) R.string.status_online else R.string.status_offline
                    )
                    callButton.isEnabled = online
                    callButton.alpha = if (online) 1f else 0.35f
                    callButton.imageTintList = ContextCompat.getColorStateList(
                        requireContext(),
                        if (online) R.color.conversation_call_icon else R.color.tb_text_muted
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.conversationUi.collect {
                    renderMessages(viewModel.conversationMessages(remoteKey))
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bulkOutbox.collect {
                    renderMessages(viewModel.conversationMessages(remoteKey))
                }
            }
        }

        fun submitMessage() {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            viewModel.syncServiceState()
            when (val err = viewModel.sendEndpointText(remoteKey, text)) {
                null -> input.text?.clear()
                "SERVICE_STOPPED" -> toast(R.string.service_not_running)
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
        viewModel.setOpenConversation(remoteKey)
    }

    override fun onDestroyView() {
        attachmentActions.release()
        super.onDestroyView()
    }

    override fun onStop() {
        viewModel.setOpenConversation(null)
        super.onStop()
    }

    private fun startPrivateCallFromConversation() {
        val activity = activity as? MainActivity ?: return
        PrivateCallNavigator.start(
            activity,
            CallLaunchContext(
                targetKey = remoteKey,
                targetLabel = remoteLabel,
                teamName = viewModel.teamDisplayName(),
                source = CallSource.CONVERSATION,
                returnTarget = CallReturnTarget.Conversation(remoteKey, remoteLabel)
            )
        )
    }

    private fun bindMessageRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        record: EndpointTextRecord
    ): View {
        val views = MessageRowUiBinder.inflateMessageRow(inflater, parent)
        val outbound = record.direction == EndpointTextDirection.OUTBOUND
        val params = views.bubble.layoutParams as FrameLayout.LayoutParams
        if (outbound) {
            params.gravity = Gravity.END
            views.bubble.setBackgroundResource(R.drawable.bg_message_bubble_outbound)
            views.sender.text = getString(R.string.conversation_sender_you)
        } else {
            params.gravity = Gravity.START
            views.bubble.setBackgroundResource(R.drawable.bg_message_bubble_inbound)
            views.sender.text = remoteLabel
        }
        views.bubble.layoutParams = params
        views.time.text = ConversationTimeFormat.formatMessageTime(record.timestampMs)
        MessageRowUiBinder.bindDirectBody(
            requireContext(),
            views.body,
            views.attachmentContainer,
            views.attachmentPreview,
            views.attachmentVoice,
            views.attachmentLocation,
            views.attachmentLocationText,
            views.attachmentAction,
            views.attachmentProgress,
            record,
            views.imageContainer,
            views.imageProgressOverlay,
            views.imageProgressBar,
            views.imageProgressText,
        )
        return views.row
    }

    private fun sendLargeAttachment(
        uri: Uri,
        kind: AttachmentKind,
        mimeType: String,
        fileName: String,
        sizeBytes: Long,
    ) {
        val caption = view?.findViewById<EditText>(R.id.editConversationInput)?.text?.toString()?.trim()
        viewModel.syncServiceState()
        when (
            val err = viewModel.enqueueEndpointBulkAttachment(
                remoteKey,
                uri,
                kind,
                mimeType,
                fileName,
                sizeBytes,
                caption?.takeIf { it.isNotEmpty() },
            )
        ) {
            null -> view?.findViewById<EditText>(R.id.editConversationInput)?.text?.clear()
            "SERVICE_STOPPED" -> toast(R.string.service_not_running)
            "UNREACHABLE" -> toast(R.string.endpoint_text_unreachable)
            "ATTACHMENT_TOO_LARGE" -> toast(R.string.message_attachment_too_large)
            "BULK_PTT_PAUSED" -> toast(R.string.message_bulk_ptt_paused)
            "SEND_FAILED", "ATTACHMENT_EMPTY" -> toast(R.string.message_attachment_send_failed)
            else -> toast(R.string.endpoint_text_failed)
        }
    }

    private fun sendAttachment(
        bytes: ByteArray,
        kind: AttachmentKind,
        mimeType: String,
        fileName: String
    ) {
        val caption = view?.findViewById<EditText>(R.id.editConversationInput)?.text?.toString()?.trim()
        viewModel.syncServiceState()
        when (
            val err = viewModel.sendEndpointAttachment(
                remoteKey,
                bytes,
                kind,
                mimeType,
                fileName,
                caption?.takeIf { it.isNotEmpty() }
            )
        ) {
            null -> view?.findViewById<EditText>(R.id.editConversationInput)?.text?.clear()
            "SERVICE_STOPPED" -> toast(R.string.service_not_running)
            "UNREACHABLE" -> toast(R.string.endpoint_text_unreachable)
            "ATTACHMENT_TOO_LARGE" -> toast(R.string.message_attachment_too_large)
            "SEND_FAILED", "ATTACHMENT_EMPTY" -> toast(R.string.message_attachment_send_failed)
            else -> toast(R.string.endpoint_text_failed)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG_CONVERSATION = "conversation"
        private const val ARG_KEY = "key"
        private const val ARG_LABEL = "label"

        fun newInstance(remoteKey: String, remoteLabel: String): ConversationFragment {
            return ConversationFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_KEY, remoteKey)
                    putString(ARG_LABEL, remoteLabel)
                }
            }
        }
    }
}
