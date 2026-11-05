package com.talkback.appprod.ui

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment

/** Collapse attach panel on Back; disabled while system picker is open. */
object ConversationAttachPanelBack {
    fun install(fragment: Fragment, root: View) {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (ConversationMorePanelBinder.handleBack(root)) {
                    refreshEnabled(root, this)
                }
            }
        }
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, callback)
        val refresh = { refreshEnabled(root, callback) }
        ConversationAttachmentPickerGate.addListener(refresh)
        fragment.viewLifecycleOwner.lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    ConversationAttachmentPickerGate.removeListener(refresh)
                    ConversationMorePanelBinder.onPanelVisibilityChanged = null
                }
            }
        )
        ConversationMorePanelBinder.onPanelVisibilityChanged = refresh
        refresh()
    }

    private fun refreshEnabled(root: View, callback: OnBackPressedCallback) {
        callback.isEnabled =
            ConversationMorePanelBinder.isPanelVisible(root) &&
                !ConversationAttachmentPickerGate.active
    }
}
