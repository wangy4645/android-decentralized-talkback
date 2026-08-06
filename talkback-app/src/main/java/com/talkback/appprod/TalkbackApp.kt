package com.talkback.appprod

import android.app.Application
import com.talkback.appprod.data.TaskProfileManager
import com.talkback.appprod.endpointtext.EndpointTextRecentStore
import com.talkback.appprod.runtime.TalkbackRuntimeManager

class TalkbackApp : Application() {
    lateinit var runtimeManager: TalkbackRuntimeManager
        private set

    /** Process-local EndpointText presentation cache (ADR-0039 V1.5). Not transport state. */
    val endpointTextRecentStore = EndpointTextRecentStore()

    var serviceRunning: Boolean = false
        internal set

    override fun onCreate() {
        super.onCreate()
        runtimeManager = TalkbackRuntimeManager(this)
        TaskProfileManager(this).ensureInitialized()
    }

    companion object {
        fun get(context: android.content.Context): TalkbackApp =
            context.applicationContext as TalkbackApp
    }
}
