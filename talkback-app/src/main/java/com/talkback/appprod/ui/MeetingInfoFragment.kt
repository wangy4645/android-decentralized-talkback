package com.talkback.appprod.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingInfoFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_meeting_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.txtSettingsTitle).setText(R.string.meeting_info_title)
        view.findViewById<View>(R.id.btnSettingsBack).setOnClickListener {
            (parentFragment as? MeetingFragment)?.hideSubPage()
        }
        view.findViewById<View>(R.id.btnCopyMeetingLink).setOnClickListener {
            val link = viewModel.buildMeetingLink()
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("meeting_link", link))
            Toast.makeText(requireContext(), R.string.meeting_link_copied, Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> bindInfo(view, state) }
            }
        }
    }

    private fun bindInfo(view: View, state: TalkUiState) {
        val container = view.findViewById<LinearLayout>(R.id.containerMeetingInfo)
        container.removeAllViews()
        val config = viewModel.loadConfig()
        val meeting = state.meeting
        val started = meeting.startedAtMs?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))
        } ?: "--"
        addInfoRow(container, getString(R.string.meeting_info_channel), state.channelTitle)
        addInfoRow(container, getString(R.string.meeting_info_id), meeting.sessionId ?: "--")
        addInfoRow(container, getString(R.string.meeting_info_creator), "${config.moduleId} / ${config.endpointId}")
        addInfoRow(container, getString(R.string.meeting_info_start_time), started)
        addInfoRow(container, getString(R.string.call_qos_codec), meeting.codecLabel)
        addInfoRow(container, getString(R.string.call_qos_network), meeting.networkLabel)
        val loss = meeting.packetLossPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "N/A"
        addInfoRow(container, getString(R.string.call_qos_loss), loss)
        val rtt = meeting.rttMs?.let { "${it}ms" } ?: "N/A"
        addInfoRow(container, getString(R.string.call_qos_rtt), rtt)
        addInfoRow(
            container,
            getString(R.string.meeting_auto_gain),
            if (meeting.autoGain) getString(R.string.settings_status_enabled) else "Off"
        )
        addInfoRow(
            container,
            getString(R.string.meeting_noise_suppression),
            if (meeting.noiseSuppression) getString(R.string.settings_status_enabled) else "Off"
        )
    }

    private fun addInfoRow(container: LinearLayout, label: String, value: String) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 12)
        }
        val title = TextView(ctx).apply {
            text = label
            setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_muted))
            textSize = 11f
        }
        val detail = TextView(ctx).apply {
            text = value
            setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_primary))
            textSize = 13f
        }
        row.addView(title)
        row.addView(detail)
        container.addView(row)
    }
}
