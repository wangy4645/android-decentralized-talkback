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

class MainActivity : AppCompatActivity() {
    private lateinit var talkViewModel: TalkViewModel
    private var receiverRegistered = false
    private var suppressNavListener = false
    private var wasConferenceActive = false

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

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
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

    fun navigateToContactsFromCall() {
        dismissCallOverlay()
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        suppressNavListener = true
        bottomNav.selectedItemId = R.id.nav_contacts
        suppressNavListener = false
        showFragment(ContactsFragment(), TAG_CONTACTS)
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
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(CallFragment.TAG_CALL) ?: run {
            findViewById<android.view.View>(R.id.callOverlayContainer).isVisible = false
            return
        }
        fm.commit {
            setReorderingAllowed(true)
            remove(existing)
        }
        findViewById<android.view.View>(R.id.callOverlayContainer).isVisible = false
    }

    fun showCallScreen(
        remoteKey: String? = null,
        remoteLabel: String? = null,
        teamName: String? = null
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

        dismissCallOverlay()
        findViewById<android.view.View>(R.id.callOverlayContainer).isVisible = true
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(
                R.id.callOverlayContainer,
                CallFragment.newInstance(remoteKey, remoteLabel, teamName),
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
        private const val TAG_TALK = "talk"
        private const val TAG_CHANNELS = "channels"
        private const val TAG_CONTACTS = "contacts"
        private const val TAG_SETTINGS = "settings"
    }
}
