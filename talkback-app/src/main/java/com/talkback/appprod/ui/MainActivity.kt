package com.talkback.appprod.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.talkback.appprod.R
import com.talkback.appprod.service.TalkbackForegroundService
import com.talkback.appprod.ui.call.CallLaunchContext
import com.talkback.appprod.ui.call.CallReturnTarget
import com.talkback.appprod.ui.call.CallSource

class MainActivity : AppCompatActivity() {
    private lateinit var talkViewModel: TalkViewModel
    private var receiverRegistered = false
    private var suppressNavListener = false
    private var wasConferenceActive = false
    private var pendingCallReturnTarget: CallReturnTarget? = null
    private var conversationHiddenForCall = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TalkbackForegroundService.ACTION_SERVICE_STATE) return
            val state = intent.getStringExtra(TalkbackForegroundService.EXTRA_SERVICE_STATE) ?: "UNKNOWN"
            val detail = intent.getStringExtra(TalkbackForegroundService.EXTRA_SERVICE_DETAIL) ?: ""
            talkViewModel.onServiceState(state, detail)
            (supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment)
                ?.updateServiceState(state, detail)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        talkViewModel = ViewModelProvider(this, TalkViewModelFactory(this))[TalkViewModel::class.java]
        requestAudioPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (savedInstanceState == null) {
            showFragment(TalkFragment(), TAG_TALK)
            bottomNav.selectedItemId = R.id.nav_talk
        }

        bottomNav.setOnItemSelectedListener { item ->
            if (suppressNavListener) return@setOnItemSelectedListener true
            if (!talkViewModel.uiState.value.call.active) {
                dismissCallOverlay()
            }
            when (item.itemId) {
                R.id.nav_talk -> showFragment(TalkFragment(), TAG_TALK)
                R.id.nav_channels -> showFragment(ChannelsFragment(), TAG_CHANNELS)
                R.id.nav_contacts -> showFragment(ContactsFragment(), TAG_CONTACTS)
                R.id.nav_settings -> showFragment(SettingsFragment(), TAG_SETTINGS)
                else -> return@setOnItemSelectedListener false
            }
            ensureCallOverlayForActiveCall()
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                talkViewModel.uiState.collect { state ->
                    handleTalkUiState(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                talkViewModel.openMeetingEvents.collect { nav ->
                    showMeetingScreen(nav)
                }
            }
        }

        talkViewModel.bindInboundToastHandler { batch ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@bindInboundToastHandler
            val senders = batch.senders.filter { hint ->
                if (hint.isChannel) {
                    !talkViewModel.isChannelConversationOpen()
                } else {
                    !talkViewModel.isConversationOpenFor(hint.key)
                }
            }
            if (senders.isEmpty()) return@bindInboundToastHandler
            val message = when {
                senders.size == 1 && senders[0].isChannel ->
                    getString(R.string.message_inbound_toast_channel, senders[0].label)
                senders.size == 1 ->
                    getString(R.string.message_inbound_toast_single, senders[0].label)
                else -> getString(
                    R.string.message_inbound_toast_multi,
                    senders.size,
                    senders.joinToString(", ") { it.label }
                )
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        handleInboundNavigationIntent(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (findViewById<View>(R.id.conversationOverlayContainer).isVisible) {
                        dismissConversation()
                        return
                    }
                    val meeting = supportFragmentManager.findFragmentByTag(MeetingFragment.TAG_MEETING)
                        as? MeetingFragment
                    if (meeting?.isAdded == true) {
                        if (meeting.childFragmentManager.backStackEntryCount > 0) {
                            meeting.hideSubPage()
                        } else {
                            meeting.handleMeetingBack()
                        }
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun handleTalkUiState(state: TalkUiState) {
        val meetingShowing = isMeetingOverlayShowing()
        val conferenceEnded = wasConferenceActive && !state.conferenceActive

        if (meetingShowing && (conferenceEnded || state.call.active)) {
            dismissMeetingOverlay()
            if (conferenceEnded && !state.call.active &&
                state.conferenceEndReason == ConferenceEndReason.REMOTE_ENDED
            ) {
                Toast.makeText(this, R.string.meeting_ended, Toast.LENGTH_SHORT).show()
            }
        }

        val invite = state.incomingMeetingInvite
        if (invite != null && !state.call.active) {
            showMeetingInviteScreen(invite)
        } else {
            dismissMeetingInviteOverlay()
        }

        if (state.call.active) {
            ensureCallOverlayForActiveCall()
        } else {
            ensureCallEndedSummaryVisible()
        }

        wasConferenceActive = state.conferenceActive
    }

    private fun shouldKeepCallOverlayVisible(): Boolean {
        if (talkViewModel.uiState.value.call.active) return true
        val callFragment = supportFragmentManager.findFragmentByTag(CallFragment.TAG_CALL) as? CallFragment
        return callFragment?.isAdded == true && callFragment.isShowingEnded()
    }

    fun ensureCallEndedSummaryVisible() {
        if (!shouldKeepCallOverlayVisible()) return
        findViewById<View>(R.id.callOverlayContainer).isVisible = true
    }

    private fun isMeetingOverlayShowing(): Boolean {
        val container = findViewById<View>(R.id.meetingOverlayContainer)
        if (!container.isVisible) return false
        val meeting = supportFragmentManager.findFragmentByTag(MeetingFragment.TAG_MEETING)
        return meeting?.isAdded == true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInboundNavigationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        talkViewModel.clearInboundNotification()
    }

    override fun onDestroy() {
        talkViewModel.bindInboundToastHandler(null)
        super.onDestroy()
    }

    fun navigateToMessagesTab() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        suppressNavListener = true
        bottomNav.selectedItemId = R.id.nav_channels
        suppressNavListener = false
        val existing = supportFragmentManager.findFragmentByTag(TAG_CHANNELS) as? ChannelsFragment
        if (existing != null) {
            existing.showMessagesTab()
        } else {
            val channels = ChannelsFragment()
            showFragment(channels, TAG_CHANNELS)
            channels.view?.post { channels.showMessagesTab() }
                ?: run { channels.showMessagesTab() }
        }
    }

    private fun handleInboundNavigationIntent(intent: Intent?) {
        if (intent == null) return
        val openMessages = intent.getBooleanExtra(EXTRA_OPEN_MESSAGES_TAB, false)
        val openChannel = intent.getBooleanExtra(EXTRA_OPEN_CHANNEL_CONVERSATION, false)
        val conversationKey = intent.getStringExtra(EXTRA_OPEN_CONVERSATION_KEY).orEmpty()
        val conversationLabel = intent.getStringExtra(EXTRA_OPEN_CONVERSATION_LABEL).orEmpty()
        if (!openMessages && !openChannel && conversationKey.isBlank()) return

        intent.removeExtra(EXTRA_OPEN_MESSAGES_TAB)
        intent.removeExtra(EXTRA_OPEN_CHANNEL_CONVERSATION)
        intent.removeExtra(EXTRA_OPEN_CONVERSATION_KEY)
        intent.removeExtra(EXTRA_OPEN_CONVERSATION_LABEL)

        if (openMessages) {
            navigateToMessagesTab()
            return
        }
        if (openChannel) {
            val state = talkViewModel.conversationUi.value
            val channelId = state.channelId.ifBlank {
                // fallback if UI state not yet refreshed
                talkViewModel.conversationUi.value.channelId
            }
            showChannelConversation(
                channelId,
                state.channelDisplayName.ifBlank { talkViewModel.teamDisplayName() }
            )
            return
        }
        if (conversationKey.isNotBlank()) {
            showConversation(
                conversationKey,
                conversationLabel.ifBlank { talkViewModel.endpointLabelForKey(conversationKey) }
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS
        )
    }

    fun navigateToContactsFromCall() {
        pendingCallReturnTarget = null
        conversationHiddenForCall = false
        dismissCallOverlayOnly()
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        suppressNavListener = true
        bottomNav.selectedItemId = R.id.nav_contacts
        suppressNavListener = false
        showFragment(ContactsFragment(), TAG_CONTACTS)
    }

    fun startPrivateCall(launch: CallLaunchContext) {
        talkViewModel.syncServiceState()
        when (val precheck = talkViewModel.precheckPrivateCall(launch.targetKey)) {
            "OFFLINE" -> {
                Toast.makeText(
                    this,
                    getString(R.string.call_cannot_call_offline, launch.targetLabel),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            "SERVICE_STOPPED" -> {
                Toast.makeText(this, R.string.service_not_running, Toast.LENGTH_SHORT).show()
                return
            }
        }
        when (val err = talkViewModel.placeCall(launch.targetKey)) {
            null -> {
                pendingCallReturnTarget = launch.returnTarget
                if (launch.source == CallSource.CONVERSATION) {
                    hideConversationOverlayForCall()
                }
                showCallScreen(
                    launch.targetKey,
                    launch.targetLabel,
                    launch.teamName,
                    launch.returnTarget
                )
            }
            "BUSY" -> Toast.makeText(
                this,
                getString(R.string.call_peer_busy, launch.targetLabel),
                Toast.LENGTH_SHORT
            ).show()
            "UNREACHABLE" -> Toast.makeText(
                this,
                R.string.call_failed_unreachable,
                Toast.LENGTH_SHORT
            ).show()
            "SERVICE_STOPPED" -> Toast.makeText(
                this,
                R.string.service_not_running,
                Toast.LENGTH_SHORT
            ).show()
            else -> Toast.makeText(this, R.string.call_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun finishCallFlow() {
        dismissCallOverlayOnly()
        applyCallReturnTarget()
    }

    fun callReturnTargetForUi(): CallReturnTarget? = pendingCallReturnTarget

    private fun hideConversationOverlayForCall() {
        val container = findViewById<View>(R.id.conversationOverlayContainer)
        if (!container.isVisible) return
        conversationHiddenForCall = true
        container.isVisible = false
    }

    private fun showConversationOverlayIfHidden() {
        if (!conversationHiddenForCall) return
        conversationHiddenForCall = false
        findViewById<View>(R.id.conversationOverlayContainer).isVisible = true
    }

    private fun applyCallReturnTarget() {
        when (val target = pendingCallReturnTarget) {
            is CallReturnTarget.Conversation -> {
                showConversationOverlayIfHidden()
            }
            CallReturnTarget.Contacts -> {
                conversationHiddenForCall = false
                val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
                suppressNavListener = true
                bottomNav.selectedItemId = R.id.nav_contacts
                suppressNavListener = false
                showFragment(ContactsFragment(), TAG_CONTACTS)
            }
            CallReturnTarget.Talk -> {
                conversationHiddenForCall = false
                val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
                suppressNavListener = true
                bottomNav.selectedItemId = R.id.nav_talk
                suppressNavListener = false
                showFragment(TalkFragment(), TAG_TALK)
            }
            null -> Unit
        }
        pendingCallReturnTarget = null
    }

    fun showConversation(remoteKey: String, remoteLabel: String) {
        findViewById<View>(R.id.conversationOverlayContainer).isVisible = true
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(
                R.id.conversationOverlayContainer,
                ConversationFragment.newInstance(remoteKey, remoteLabel),
                ConversationFragment.TAG_CONVERSATION
            )
        }
    }

    fun showChannelConversation(channelId: String, channelLabel: String) {
        findViewById<View>(R.id.conversationOverlayContainer).isVisible = true
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(
                R.id.conversationOverlayContainer,
                ChannelConversationFragment.newInstance(channelId, channelLabel),
                ChannelConversationFragment.TAG_CHANNEL_CONVERSATION
            )
        }
    }

    fun dismissConversation() {
        val existing = supportFragmentManager.findFragmentByTag(ConversationFragment.TAG_CONVERSATION)
            ?: supportFragmentManager.findFragmentByTag(
                ChannelConversationFragment.TAG_CHANNEL_CONVERSATION
            )
        if (existing != null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                remove(existing)
            }
        }
        findViewById<View>(R.id.conversationOverlayContainer).isVisible = false
        talkViewModel.setOpenConversation(null)
        talkViewModel.setOpenChannelConversation(null)
    }

    fun showMeetingScreen(target: MeetingNavigation = MeetingNavigation.MAIN) {
        val existing =
            supportFragmentManager.findFragmentByTag(MeetingFragment.TAG_MEETING) as? MeetingFragment
        if (existing?.isAdded == true) {
            findViewById<View>(R.id.meetingOverlayContainer).isVisible = true
            findViewById<View>(R.id.bottomNav).isVisible = false
            when (target) {
                MeetingNavigation.MEMBERS -> existing.showSubPage(MeetingMembersFragment())
                MeetingNavigation.OPTIONS -> existing.showSubPage(MeetingOptionsFragment())
                MeetingNavigation.INVITE -> existing.showSubPage(InviteMembersFragment())
                MeetingNavigation.MAIN -> Unit
            }
            return
        }
        findViewById<View>(R.id.meetingOverlayContainer).isVisible = true
        findViewById<View>(R.id.bottomNav).isVisible = false
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(
                R.id.meetingOverlayContainer,
                MeetingFragment.newInstance(target),
                MeetingFragment.TAG_MEETING
            )
        }
    }

    fun dismissMeetingOverlay() {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(MeetingFragment.TAG_MEETING) ?: run {
            findViewById<View>(R.id.meetingOverlayContainer).isVisible = false
            findViewById<View>(R.id.bottomNav).isVisible = true
            talkViewModel.resetTalkTabToPtt()
            return
        }
        fm.commit {
            setReorderingAllowed(true)
            remove(existing)
        }
        findViewById<View>(R.id.meetingOverlayContainer).isVisible = false
        findViewById<View>(R.id.bottomNav).isVisible = true
        talkViewModel.resetTalkTabToPtt()
    }

    private fun showMeetingInviteScreen(invite: IncomingMeetingInviteUi) {
        val existing = supportFragmentManager.findFragmentByTag(MeetingInviteFragment.TAG_MEETING_INVITE)
            as? MeetingInviteFragment
        if (existing?.isAdded == true) {
            findViewById<View>(R.id.callOverlayContainer).isVisible = true
            return
        }
        dismissCallOverlay()
        findViewById<View>(R.id.callOverlayContainer).isVisible = true
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(
                R.id.callOverlayContainer,
                MeetingInviteFragment.newInstance(invite.hostLabel, invite.channelTitle),
                MeetingInviteFragment.TAG_MEETING_INVITE
            )
        }
    }

    private fun dismissMeetingInviteOverlay() {
        val existing = supportFragmentManager.findFragmentByTag(MeetingInviteFragment.TAG_MEETING_INVITE)
            ?: run {
                if (!shouldKeepCallOverlayVisible()) {
                    findViewById<View>(R.id.callOverlayContainer).isVisible = false
                }
                return
            }
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            remove(existing)
        }
        if (!shouldKeepCallOverlayVisible()) {
            findViewById<View>(R.id.callOverlayContainer).isVisible = false
        }
    }

    fun dismissCallOverlay() {
        dismissCallOverlayOnly()
        applyCallReturnTarget()
    }

    private fun dismissCallOverlayOnly() {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(CallFragment.TAG_CALL) ?: run {
            findViewById<View>(R.id.callOverlayContainer).isVisible = false
            return
        }
        fm.commit {
            setReorderingAllowed(true)
            remove(existing)
        }
        findViewById<View>(R.id.callOverlayContainer).isVisible = false
    }

    fun showCallScreen(
        remoteKey: String? = null,
        remoteLabel: String? = null,
        teamName: String? = null,
        returnTarget: CallReturnTarget? = null
    ) {
        val call = talkViewModel.uiState.value.call
        val sessionId = call.sessionId
        val existing =
            supportFragmentManager.findFragmentByTag(CallFragment.TAG_CALL) as? CallFragment
        if (existing?.isAdded == true && !existing.isShowingEnded()) {
            val displayed = existing.displayedSessionId()
            if (sessionId == null || displayed == null || displayed == sessionId) {
                findViewById<android.view.View>(R.id.callOverlayContainer).isVisible = true
                return
            }
        }

        dismissCallOverlayOnly()
        findViewById<View>(R.id.callOverlayContainer).isVisible = true
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(
                R.id.callOverlayContainer,
                CallFragment.newInstance(remoteKey, remoteLabel, teamName, returnTarget),
                CallFragment.TAG_CALL
            )
        }
    }

    private fun ensureCallOverlayForActiveCall() {
        val call = talkViewModel.uiState.value.call
        if (!call.active) return
        val sessionId = call.sessionId ?: return
        val existing =
            supportFragmentManager.findFragmentByTag(CallFragment.TAG_CALL) as? CallFragment
        if (existing?.isAdded == true && !existing.isShowingEnded()) {
            val displayed = existing.displayedSessionId()
            if (displayed == null || displayed == sessionId) {
                findViewById<android.view.View>(R.id.callOverlayContainer).isVisible = true
                return
            }
        }
        showCallScreen(call.remoteKey, call.remoteLabel, call.teamName)
    }

    override fun onStart() {
        super.onStart()
        talkViewModel.startPolling()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                IntentFilter(TalkbackForegroundService.ACTION_SERVICE_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        talkViewModel.stopPolling()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragmentContainer, fragment, tag)
        }
    }

    private fun requestAudioPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION_KEY = "open_conversation_key"
        const val EXTRA_OPEN_CONVERSATION_LABEL = "open_conversation_label"
        const val EXTRA_OPEN_MESSAGES_TAB = "open_messages_tab"
        const val EXTRA_OPEN_CHANNEL_CONVERSATION = "open_channel_conversation"

        private const val TAG_TALK = "talk"
        private const val TAG_CHANNELS = "channels"
        private const val TAG_CONTACTS = "contacts"
        private const val TAG_SETTINGS = "settings"
        private const val REQUEST_POST_NOTIFICATIONS = 1003
    }
}
