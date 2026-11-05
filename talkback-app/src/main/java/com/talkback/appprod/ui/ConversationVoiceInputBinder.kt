package com.talkback.appprod.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.talkback.appprod.R

/** Input bar voice mode: toggle + hold-to-talk (WeChat-style). */
object ConversationVoiceInputBinder {
    private const val CANCEL_SLIDE_UP_DP = 80f

    @SuppressLint("ClickableViewAccessibility")
    fun bind(root: View, actions: ConversationAttachmentActions) {
        val input = root.findViewById<EditText>(R.id.editConversationInput)
        val send = root.findViewById<View>(R.id.btnConversationSend)
        val holdBar = root.findViewById<View>(R.id.conversationInputHoldBar)
        val holdLabel = root.findViewById<TextView>(R.id.txtConversationInputHold)
        val btnMode = root.findViewById<ImageView>(R.id.btnConversationVoiceMode)
        val ctx = root.context
        val cancelSlidePx = CANCEL_SLIDE_UP_DP * ctx.resources.displayMetrics.density
        var voiceMode = false
        var downY = 0f
        var cancelArmed = false

        fun resetHoldUi() {
            cancelArmed = false
            holdBar.setBackgroundResource(R.drawable.bg_conversation_input)
            holdLabel.setText(R.string.message_voice_hold_to_talk)
            holdLabel.setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_primary))
        }

        fun setRecordingUi() {
            holdLabel.setText(R.string.message_voice_hold_release)
            holdBar.setBackgroundResource(R.drawable.bg_conversation_voice_hold_active)
        }

        fun setCancelUi() {
            holdLabel.setText(R.string.message_voice_hold_cancel)
            holdBar.setBackgroundResource(R.drawable.bg_conversation_voice_hold_cancel)
            holdLabel.setTextColor(ContextCompat.getColor(ctx, R.color.tb_danger))
        }

        fun applyInputMode() {
            input.isVisible = !voiceMode
            send.isVisible = !voiceMode
            holdBar.isVisible = voiceMode
            if (voiceMode) {
                ConversationMorePanelBinder.collapsePanel(root)
            }
            if (!voiceMode) {
                resetHoldUi()
                if (actions.isVoiceRecording()) actions.cancelVoiceRecording()
            }
            btnMode.setImageResource(
                if (voiceMode) R.drawable.ic_message_input_keyboard else R.drawable.ic_message_attach_mic
            )
            btnMode.contentDescription = ctx.getString(
                if (voiceMode) R.string.message_text_mode_cd else R.string.message_voice_mode_cd
            )
        }

        applyInputMode()

        btnMode.setOnClickListener {
            voiceMode = !voiceMode
            applyInputMode()
        }

        holdBar.setOnTouchListener { _, event ->
            if (!voiceMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    cancelArmed = false
                    if (actions.startVoiceRecording()) setRecordingUi()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!actions.isVoiceRecording()) return@setOnTouchListener true
                    val slideUp = downY - event.rawY
                    if (slideUp > cancelSlidePx) {
                        if (!cancelArmed) {
                            cancelArmed = true
                            setCancelUi()
                        }
                    } else if (cancelArmed) {
                        cancelArmed = false
                        setRecordingUi()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (actions.isVoiceRecording()) {
                        if (cancelArmed) actions.cancelVoiceRecording()
                        else actions.finishVoiceRecordingAndSend()
                    }
                    resetHoldUi()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    actions.cancelVoiceRecording()
                    resetHoldUi()
                    true
                }
                else -> false
            }
        }
    }
}
