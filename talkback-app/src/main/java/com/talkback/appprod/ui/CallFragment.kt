package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import com.talkback.core.session.UnicastCallPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CallFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    private var callStartMs: Long? = null
    private var showingEnded = false
    private var timerJob: Job? = null
    private var waveJob: Job? = null
    private var lastCallSnapshot: CallUiState? = null
    private var displayedSessionId: String? = null

    private lateinit var panelActive: View
    private lateinit var panelEnded: View
    private lateinit var txtHeaderName: TextView
    private lateinit var txtHeaderTeam: TextView
    private lateinit var txtCallRemote: TextView
    private lateinit var txtCallStatus: TextView
    private lateinit var txtCallTimer: TextView
    private lateinit var rowQos: View
    private lateinit var rowIncoming: View
    private lateinit var rowControls: View
    private lateinit var btnEndCall: View
    private lateinit var imgWaveLeft: ImageView
    private lateinit var imgWaveRight: ImageView
    private lateinit var imgStatusWave: ImageView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_call, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        panelActive = view.findViewById(R.id.panelActiveCall)
        panelEnded = view.findViewById(R.id.panelCallEnded)
        txtHeaderName = view.findViewById(R.id.txtHeaderName)
        txtHeaderTeam = view.findViewById(R.id.txtHeaderTeam)
        txtCallRemote = view.findViewById(R.id.txtCallRemote)
        txtCallStatus = view.findViewById(R.id.txtCallStatus)
        txtCallTimer = view.findViewById(R.id.txtCallTimer)
        rowQos = view.findViewById(R.id.rowQosStats)
        rowIncoming = view.findViewById(R.id.rowIncomingActions)
        rowControls = view.findViewById(R.id.rowInCallControls)
        btnEndCall = view.findViewById(R.id.btnEndCall)
        imgWaveLeft = view.findViewById(R.id.imgWaveLeft)
        imgWaveRight = view.findViewById(R.id.imgWaveRight)
        imgStatusWave = view.findViewById(R.id.imgStatusWave)

        val argLabel = arguments?.getString(ARG_REMOTE_LABEL)
        val argTeam = arguments?.getString(ARG_TEAM_NAME)
        if (!argLabel.isNullOrBlank()) {
            bindIdentity(argLabel, argTeam.orEmpty())
        }

        setupControlButton(
            view.findViewById(R.id.btnMute),
            R.drawable.ic_mic_ptt,
            getString(R.string.call_control_mute)
        ) {
            viewModel.toggleCallMute()
        }

        val speakerControl = view.findViewById<View>(R.id.btnSpeaker)
        val headsetControl = view.findViewById<View>(R.id.btnHeadset)
        setupControlButton(
            speakerControl,
            R.drawable.ic_toolbar_volume,
            getString(R.string.call_control_speaker)
        ) {
            CallAudioRouteHelper.apply(requireContext(), CallAudioRoute.SPEAKER)
            CallAudioRouteUi.highlight(speakerControl, headsetControl, CallAudioRoute.SPEAKER)
        }

        setupControlButton(
            headsetControl,
            R.drawable.ic_volume,
            getString(R.string.call_control_headset)
        ) {
            CallAudioRouteHelper.apply(requireContext(), CallAudioRoute.EARPIECE)
            CallAudioRouteUi.highlight(speakerControl, headsetControl, CallAudioRoute.EARPIECE)
        }

        setupControlButton(
            view.findViewById(R.id.btnMore),
            R.drawable.ic_more_horiz,
            getString(R.string.call_control_more)
        ) {
            Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.btnAccept).setOnClickListener {
            viewModel.acceptIncomingCall()
        }
        view.findViewById<View>(R.id.btnReject).setOnClickListener {
            viewModel.rejectIncomingCall()
            closeSelf()
        }
        btnEndCall.setOnClickListener { finishCall() }
        view.findViewById<View>(R.id.btnCallBack).setOnClickListener { finishCall() }
        view.findViewById<View>(R.id.btnBackToContacts).setOnClickListener {
            (activity as? MainActivity)?.navigateToContactsFromCall()
        }

        CallAudioRouteHelper.apply(requireContext(), CallAudioRoute.SPEAKER)
        CallAudioRouteUi.highlight(speakerControl, headsetControl, CallAudioRoute.SPEAKER)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val call = state.call
                    lastCallSnapshot = call.takeIf { it.active } ?: lastCallSnapshot

                    if (call.active && showingEnded) {
                        resetForNewCall()
                    }

                    if (!call.active && !showingEnded) {
                        if (callStartMs != null) {
                            showEndedPanel(callStartMs!!, System.currentTimeMillis(), lastCallSnapshot)
                        } else {
                            closeSelf()
                        }
                        return@collect
                    }
                    if (!call.active) return@collect

                    displayedSessionId = call.sessionId

                    val label = call.remoteLabel ?: argLabel ?: getString(R.string.call_unknown_remote)
                    val team = call.teamName ?: argTeam ?: viewModel.teamDisplayName()
                    bindIdentity(label, team)
                    txtCallRemote.text = label

                    val isIncomingRinging =
                        call.phase == UnicastCallPhase.RINGING && !call.localInitiated
                    rowIncoming.isVisible = isIncomingRinging
                    rowControls.isVisible = !isIncomingRinging && call.phase == UnicastCallPhase.CONNECTED
                    btnEndCall.isVisible = !isIncomingRinging
                    rowQos.isVisible = call.phase == UnicastCallPhase.CONNECTED

                    txtCallStatus.text = statusText(call)
                    imgStatusWave.isVisible = call.phase == UnicastCallPhase.CONNECTED
                    updateMuteLabel(view, call.muted)
                    bindQos(view, call)

                    when (call.phase) {
                        UnicastCallPhase.CONNECTED -> {
                            if (callStartMs == null) callStartMs = System.currentTimeMillis()
                            startTimer()
                            startWaveAnimation()
                        }
                        else -> {
                            stopTimer()
                            stopWaveAnimation()
                            txtCallTimer.isVisible = false
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startPolling()
        viewModel.refresh()
    }

    override fun onPause() {
        stopTimer()
        stopWaveAnimation()
        viewModel.stopPolling()
        super.onPause()
    }

    private fun bindIdentity(label: String, team: String) {
        txtHeaderName.text = label
        txtHeaderTeam.text = team
        txtCallRemote.text = label
    }

    private fun setupControlButton(
        root: View,
        iconRes: Int,
        label: String,
        onClick: () -> Unit
    ) {
        root.findViewById<ImageView>(R.id.imgControlIcon).setImageResource(iconRes)
        root.findViewById<TextView>(R.id.txtControlLabel).text = label
        root.setOnClickListener { onClick() }
    }

    private fun updateMuteLabel(view: View, muted: Boolean) {
        val muteRoot = view.findViewById<View>(R.id.btnMute)
        val icon = muteRoot.findViewById<ImageView>(R.id.imgControlIcon)
        val label = muteRoot.findViewById<TextView>(R.id.txtControlLabel)
        val circle = muteRoot.findViewById<FrameLayout>(R.id.btnControlCircle)
        val ctx = requireContext()
        if (muted) {
            icon.setImageResource(R.drawable.ic_mic_off)
            icon.imageTintList = null
            label.text = getString(R.string.call_control_unmute)
            label.setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_secondary))
            circle.setBackgroundResource(R.drawable.bg_meeting_mute_control_active)
        } else {
            icon.setImageResource(R.drawable.ic_mic_ptt)
            icon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.tb_text_primary)
            label.text = getString(R.string.call_control_mute)
            label.setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_muted))
            circle.setBackgroundResource(R.drawable.bg_call_control_circle)
        }
    }

    private fun bindQos(view: View, call: CallUiState) {
        view.findViewById<TextView>(R.id.txtQosNetwork).text = call.networkLabel
        view.findViewById<TextView>(R.id.txtQosRtt).text =
            call.rttMs?.let { "${it}ms" } ?: getString(R.string.call_qos_na)
        view.findViewById<TextView>(R.id.txtQosLoss).text =
            call.packetLossPercent?.let { String.format(Locale.US, "%.1f%%", it) }
                ?: getString(R.string.call_qos_na)
        view.findViewById<TextView>(R.id.txtQosCodec).text = call.codecLabel
    }

    private fun statusText(call: CallUiState): String {
        return when (call.phase) {
            UnicastCallPhase.RINGING ->
                if (call.localInitiated) getString(R.string.call_status_ringing_out)
                else getString(R.string.call_status_incoming)
            UnicastCallPhase.CONNECTING -> getString(R.string.call_status_connecting)
            UnicastCallPhase.CONNECTED -> getString(R.string.call_status_speaking)
            null -> getString(R.string.call_status_connecting)
        }
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val start = callStartMs
                if (start != null) {
                    txtCallTimer.isVisible = true
                    txtCallTimer.text = formatDuration(System.currentTimeMillis() - start)
                }
                delay(1_000L)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun startWaveAnimation() {
        if (waveJob?.isActive == true) return
        waveJob = viewLifecycleOwner.lifecycleScope.launch {
            var on = true
            while (isActive) {
                val alpha = if (on) 1f else 0.25f
                imgWaveLeft.alpha = alpha
                imgWaveRight.alpha = alpha
                on = !on
                delay(450L)
            }
        }
    }

    private fun stopWaveAnimation() {
        waveJob?.cancel()
        waveJob = null
        imgWaveLeft.alpha = 0f
        imgWaveRight.alpha = 0f
    }

    private fun finishCall() {
        val start = callStartMs
        val snapshot = lastCallSnapshot ?: viewModel.uiState.value.call
        val wasConnected = start != null || snapshot?.phase == UnicastCallPhase.CONNECTED
        viewModel.hangupActiveCall()
        if (wasConnected && start != null) {
            showEndedPanel(start, System.currentTimeMillis(), snapshot)
        } else {
            closeSelf()
        }
    }

    fun isShowingEnded(): Boolean = showingEnded

    fun displayedSessionId(): String? = displayedSessionId

    private fun resetForNewCall() {
        showingEnded = false
        callStartMs = null
        lastCallSnapshot = null
        displayedSessionId = null
        panelEnded.isVisible = false
        panelActive.isVisible = true
    }

    private fun showEndedPanel(startMs: Long, endMs: Long, snapshot: CallUiState?) {
        showingEnded = true
        stopTimer()
        stopWaveAnimation()
        panelActive.isVisible = false
        panelEnded.isVisible = true

        val duration = endMs - startMs
        requireView().findViewById<TextView>(R.id.txtEndedDuration).text =
            getString(R.string.call_summary_duration) + ": " + formatDuration(duration)

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val lines = listOf(
            getString(R.string.call_summary_start) to fmt.format(Date(startMs)),
            getString(R.string.call_summary_end) to fmt.format(Date(endMs)),
            getString(R.string.call_summary_duration) to formatDuration(duration),
            getString(R.string.call_summary_avg_rtt) to (
                snapshot?.rttMs?.let { "${it}ms" } ?: getString(R.string.call_qos_na)
                ),
            getString(R.string.call_summary_packet_loss) to (
                snapshot?.packetLossPercent?.let { String.format(Locale.US, "%.1f%%", it) }
                    ?: getString(R.string.call_qos_na)
                ),
            getString(R.string.call_summary_network) to (snapshot?.networkLabel ?: "N/A")
        )
        requireView().findViewById<TextView>(R.id.txtSummaryBody).text = lines.joinToString("\n") { (k, v) ->
            getString(R.string.call_summary_line, k, v)
        }
        (activity as? MainActivity)?.ensureCallEndedSummaryVisible()
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun closeSelf() {
        if (!isAdded) return
        (activity as? MainActivity)?.dismissCallOverlay()
    }

    companion object {
        const val TAG_CALL = "call"
        private const val ARG_REMOTE_KEY = "remoteKey"
        private const val ARG_REMOTE_LABEL = "remoteLabel"
        private const val ARG_TEAM_NAME = "teamName"

        fun newInstance(
            remoteKey: String? = null,
            remoteLabel: String? = null,
            teamName: String? = null
        ): CallFragment = CallFragment().apply {
            arguments = Bundle().apply {
                remoteKey?.let { putString(ARG_REMOTE_KEY, it) }
                remoteLabel?.let { putString(ARG_REMOTE_LABEL, it) }
                teamName?.let { putString(ARG_TEAM_NAME, it) }
            }
        }
    }
}
