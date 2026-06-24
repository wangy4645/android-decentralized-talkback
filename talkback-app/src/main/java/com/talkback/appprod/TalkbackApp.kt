package com.talkback.appprod

import android.app.Application
import com.talkback.appprod.data.TaskProfileManager
import com.talkback.appprod.runtime.TalkbackRuntimeManager

class TalkbackApp : Application() {
    lateinit var runtimeManager: TalkbackRuntimeManager
        private set

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
