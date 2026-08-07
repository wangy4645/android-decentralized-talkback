package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import com.talkback.appprod.ui.call.CallReturnTarget
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
    private var levelMeterJob: Job? = null
    private var lastCallSnapshot: CallUiState? = null
    private var displayedSessionId: String? = null
    private var callConnected = false

    private lateinit var panelActive: View
    private lateinit var panelEnded: View
    private lateinit var txtHeaderName: TextView
    private lateinit var txtHeaderTeam: TextView
    private lateinit var txtCallStatus: TextView
    private lateinit var txtCallTimer: TextView
    private lateinit var txtBadgeModule: TextView
    private lateinit var txtBadgeEndpoint: TextView
    private lateinit var txtConnectionStatus: TextView
    private lateinit var chipConnectionStatus: View
    private lateinit var bannerCallStatus: View
    private lateinit var rowAudioMeter: View
    private lateinit var meterCallVolume: CallHorizontalVolumeMeterView
    private lateinit var rowIncoming: View
    private lateinit var rowControls: View
    private lateinit var btnEndCall: View
    private lateinit var speakerControl: View
    private lateinit var audioRouteControl: View

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
        txtCallStatus = view.findViewById(R.id.txtCallStatus)
        txtCallTimer = view.findViewById(R.id.txtCallTimer)
        txtBadgeModule = view.findViewById(R.id.txtBadgeModule)
        txtBadgeEndpoint = view.findViewById(R.id.txtBadgeEndpoint)
        txtConnectionStatus = view.findViewById(R.id.txtConnectionStatus)
        chipConnectionStatus = view.findViewById(R.id.chipConnectionStatus)
        bannerCallStatus = view.findViewById(R.id.bannerCallStatus)
        rowAudioMeter = view.findViewById(R.id.rowAudioMeter)
        meterCallVolume = view.findViewById(R.id.meterCallVolume)
        rowIncoming = view.findViewById(R.id.rowIncomingActions)
        rowControls = view.findViewById(R.id.rowInCallControls)
        btnEndCall = view.findViewById(R.id.btnEndCall)
        speakerControl = view.findViewById(R.id.btnSpeaker)
        audioRouteControl = view.findViewById(R.id.btnAudioRoute)

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

        setupControlButton(
            speakerControl,
            R.drawable.ic_toolbar_volume,
            getString(R.string.call_control_speaker)
        ) {
            val next = if (CallAudioRouteHelper.current() == CallAudioRoute.SPEAKER) {
                CallAudioRoute.EARPIECE
            } else {
                CallAudioRoute.SPEAKER
            }
            applyAudioRoute(next)
        }

        setupControlButton(
            audioRouteControl,
            R.drawable.ic_audio_route,
            getString(R.string.call_control_audio_route)
        ) {
            showAudioRoutePicker()
        }

        view.findViewById<View>(R.id.btnHeaderMore).setOnClickListener { anchor ->
            showMoreMenu(anchor)
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
        bindReturnTargetButton(view)

        applyAudioRoute(CallAudioRoute.SPEAKER)

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

                    val isIncomingRinging =
                        call.phase == UnicastCallPhase.RINGING && !call.localInitiated
                    rowIncoming.isVisible = isIncomingRinging
                    rowControls.isVisible = !isIncomingRinging && call.phase == UnicastCallPhase.CONNECTED
                    btnEndCall.isVisible = !isIncomingRinging

                    val connected = call.phase == UnicastCallPhase.CONNECTED
                    if (connected != callConnected) {
                        callConnected = connected
                        if (connected) {
                            startLevelMeterPolling()
                        } else {
                            stopLevelMeterPolling()
                        }
                    }
                    bindPhaseChrome(call)
                    updateMuteLabel(view, call.muted)

                    when (call.phase) {
                        UnicastCallPhase.CONNECTED -> {
                            if (callStartMs == null) callStartMs = System.currentTimeMillis()
                            startTimer()
                        }
                        else -> {
                            stopTimer()
                            rowAudioMeter.isVisible = false
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
        if (callConnected) startLevelMeterPolling()
    }

    override fun onPause() {
        stopTimer()
        stopLevelMeterPolling()
        viewModel.stopPolling()
        super.onPause()
    }

    private fun bindReturnTargetButton(view: View) {
        val btn = view.findViewById<TextView>(R.id.btnBackToContacts)
        val activity = activity as? MainActivity
        val labelRes = when (activity?.callReturnTargetForUi()) {
            is CallReturnTarget.Conversation -> R.string.call_back_to_conversation
            CallReturnTarget.Talk -> R.string.call_back_to_talk
            else -> R.string.call_back_to_contacts
        }
        btn.text = getString(labelRes)
        btn.setOnClickListener {
            activity?.finishCallFlow()
        }
    }

    private fun bindIdentity(label: String, team: String) {
        txtHeaderName.text = label
        txtHeaderTeam.text = team
        val parts = label.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        txtBadgeModule.text = parts.getOrNull(0) ?: label.take(3).ifBlank { "?" }
        txtBadgeEndpoint.text = parts.getOrNull(1) ?: "—"
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

    private fun bindPhaseChrome(call: CallUiState) {
        val connected = call.phase == UnicastCallPhase.CONNECTED
        chipConnectionStatus.isVisible = connected
        if (connected) {
            txtConnectionStatus.text = getString(R.string.call_status_connected_pill)
        }

        if (!connected) {
            val bannerText = when (call.phase) {
                UnicastCallPhase.RINGING ->
                    if (call.localInitiated) getString(R.string.call_status_ringing_out)
                    else getString(R.string.call_status_incoming)
                UnicastCallPhase.CONNECTING -> getString(R.string.call_status_connecting)
                null -> getString(R.string.call_status_connecting)
                else -> null
            }
            if (bannerText != null) {
                bannerCallStatus.isVisible = true
                bannerCallStatus.alpha = 1f
                txtCallStatus.text = bannerText
            } else {
                bannerCallStatus.isVisible = false
            }
            meterCallVolume.isVisible = false
            rowAudioMeter.isVisible = false
            resetAudioVisuals()
            return
        }

        rowAudioMeter.isVisible = true
        meterCallVolume.isVisible = true
        bannerCallStatus.isVisible = true
        bannerCallStatus.alpha = 0f
    }

    private fun updateSpeakingBanner(remoteSpeaking: Boolean) {
        if (!bannerCallStatus.isVisible) return
        bannerCallStatus.alpha = if (remoteSpeaking) 1f else 0f
        if (remoteSpeaking) {
            txtCallStatus.text = getString(R.string.call_status_remote_speaking)
        }
    }

    private fun updateAudioVisuals(remoteLevel: Float, localLevel: Float) {
        val meterLevel = when {
            remoteLevel >= SPEAKING_THRESHOLD -> remoteLevel
            localLevel >= SPEAKING_THRESHOLD -> localLevel
            else -> maxOf(remoteLevel, localLevel) * 0.45f
        }
        meterCallVolume.setLevel(meterLevel.coerceIn(0f, 1f))

        val remoteSpeaking = remoteLevel >= SPEAKING_THRESHOLD
        updateSpeakingBanner(remoteSpeaking)
    }

    private fun resetAudioVisuals() {
        meterCallVolume.setLevel(0f)
        updateSpeakingBanner(false)
    }

    private fun startLevelMeterPolling() {
        if (levelMeterJob?.isActive == true) return
        levelMeterJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val (remote, local) = viewModel.unicastCallAudioLevels()
                updateAudioVisuals(remote, local)
                delay(LEVEL_POLL_MS)
            }
        }
    }

    private fun stopLevelMeterPolling() {
        levelMeterJob?.cancel()
        levelMeterJob = null
        resetAudioVisuals()
    }

    private fun applyAudioRoute(route: CallAudioRoute) {
        CallAudioRouteHelper.apply(requireContext(), route)
        CallAudioRouteUi.highlightSpeaker(speakerControl, route == CallAudioRoute.SPEAKER)
        CallAudioRouteUi.highlightControl(audioRouteControl, false)
    }

    private fun showAudioRoutePicker() {
        val labels = arrayOf(
            getString(R.string.call_audio_route_speaker),
            getString(R.string.call_audio_route_earpiece),
            getString(R.string.call_audio_route_headset)
        )
        val routes = arrayOf(
            CallAudioRoute.SPEAKER,
            CallAudioRoute.EARPIECE,
            CallAudioRoute.HEADSET
        )
        val checked = routes.indexOf(CallAudioRouteHelper.current()).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.call_audio_route_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                applyAudioRoute(routes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_DIAGNOSTICS, 0, R.string.call_more_diagnostics)
        popup.menu.add(0, MENU_RECORDING, 1, R.string.call_more_recording)
        popup.menu.add(0, MENU_SHARE_LOCATION, 2, R.string.call_more_share_location)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_DIAGNOSTICS -> {
                    showDiagnostics()
                    true
                }
                MENU_RECORDING, MENU_SHARE_LOCATION -> {
                    Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showDiagnostics() {
        val call = lastCallSnapshot ?: viewModel.uiState.value.call
        val body = listOf(
            getString(R.string.call_qos_network) to call.networkLabel,
            getString(R.string.call_qos_rtt) to (
                call.rttMs?.let { "${it}ms" } ?: getString(R.string.call_qos_na)
                ),
            getString(R.string.call_qos_loss) to (
                call.packetLossPercent?.let { String.format(Locale.US, "%.1f%%", it) }
                    ?: getString(R.string.call_qos_na)
                ),
            getString(R.string.call_qos_codec) to call.codecLabel
        ).joinToString("\n") { (k, v) ->
            getString(R.string.call_diagnostics_line, k, v)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.call_diagnostics_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val start = callStartMs
                if (start != null) {
                    rowAudioMeter.isVisible = true
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
        callConnected = false
        lastCallSnapshot = null
        displayedSessionId = null
        panelEnded.isVisible = false
        panelActive.isVisible = true
    }

    private fun showEndedPanel(startMs: Long, endMs: Long, snapshot: CallUiState?) {
        showingEnded = true
        stopTimer()
        stopLevelMeterPolling()
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
        private const val MENU_DIAGNOSTICS = 1
        private const val MENU_RECORDING = 2
        private const val MENU_SHARE_LOCATION = 3
        private const val SPEAKING_THRESHOLD = 0.06f
        private const val LEVEL_POLL_MS = 80L

        fun newInstance(
            remoteKey: String? = null,
            remoteLabel: String? = null,
            teamName: String? = null,
            @Suppress("UNUSED_PARAMETER") returnTarget: CallReturnTarget? = null
        ): CallFragment = CallFragment().apply {
            arguments = Bundle().apply {
                remoteKey?.let { putString(ARG_REMOTE_KEY, it) }
                remoteLabel?.let { putString(ARG_REMOTE_LABEL, it) }
                teamName?.let { putString(ARG_TEAM_NAME, it) }
            }
        }
    }
}
