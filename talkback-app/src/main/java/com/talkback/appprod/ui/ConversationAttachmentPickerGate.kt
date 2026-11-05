package com.talkback.appprod.ui

import java.util.concurrent.CopyOnWriteArrayList

/** True while system file/image picker is in front. Host must not intercept Back. */
object ConversationAttachmentPickerGate {
    @Volatile
    var active: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        listeners.forEach { it.invoke() }
    }
}
