package com.talkback.appprod

import android.app.Application
import com.talkback.appprod.data.TaskProfileManager
import com.talkback.appprod.endpointtext.ChannelConversationStore
import com.talkback.appprod.endpointtext.ConversationStore
import com.talkback.appprod.endpointtext.EndpointTextInboundNotifier
import com.talkback.appprod.runtime.TalkbackRuntimeManager
import kotlinx.coroutines.MainScope

class TalkbackApp : Application() {
    lateinit var runtimeManager: TalkbackRuntimeManager
        private set

    /** Process-local Tactical Message presentation cache. Not transport state. */
    val conversationStore = ConversationStore()

    /** Process-local Channel Message presentation cache (ADR-0041). */
    val channelConversationStore = ChannelConversationStore()

    lateinit var endpointTextInboundNotifier: EndpointTextInboundNotifier
        private set

    var serviceRunning: Boolean = false
        internal set

    override fun onCreate() {
        super.onCreate()
        runtimeManager = TalkbackRuntimeManager(this)
        endpointTextInboundNotifier = EndpointTextInboundNotifier(this, MainScope())
        TaskProfileManager(this).ensureInitialized()
    }

    companion object {
        fun get(context: android.content.Context): TalkbackApp =
            context.applicationContext as TalkbackApp
    }
}
