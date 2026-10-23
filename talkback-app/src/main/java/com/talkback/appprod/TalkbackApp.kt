package com.talkback.appprod

import android.app.Application
import com.talkback.appprod.data.TaskProfileManager
import com.talkback.appprod.endpointtext.ChannelConversationStore
import com.talkback.appprod.endpointtext.ConversationStore
import com.talkback.appprod.endpointtext.EndpointTextInboundNotifier
import com.talkback.appprod.runtime.TalkbackRuntimeManager
import com.talkback.core.conference.aqc.AqcRuntime
import com.talkback.core.conference.session.integration.MixCombineModeFieldExperiment
import com.talkback.core.conference.session.integration.MixOutputMakeupFieldExperiment
import com.talkback.core.conference.session.integration.D2CaptureObserveBridge
import com.talkback.core.conference.session.integration.D2SendChainRuntime
import com.talkback.core.webrtc.CapturePcmAbDiagnostic
import com.talkback.core.conference.session.integration.M01GatedTxEncodeLevelRuntime
import com.talkback.core.conference.session.integration.ShadowTxEncodePcmFieldCapture
import java.io.File
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
        // Opt-in only: unset debug.talkback.aqc.* keeps AQC flags at production defaults (off).
        AqcRuntime.applyOptionalDebugSystemProperties()
        ShadowTxEncodePcmFieldCapture.installApplicationContext(this)
        ShadowTxEncodePcmFieldCapture.applyOptionalDebugSystemProperties()
        M01GatedTxEncodeLevelRuntime.applyOptionalDebugSystemProperties()
        D2SendChainRuntime.applyOptionalDebugSystemProperties()
        if (D2SendChainRuntime.captureChainObserveEnabled) {
            CapturePcmAbDiagnostic.enabled = true
            D2CaptureObserveBridge.installIfNeeded()
        } else {
            CapturePcmAbDiagnostic.enabled = false
            D2CaptureObserveBridge.clear()
        }
        // Mix A/B: filesDir/mix_combine_field.flag → AVG|SUM; missing → keep EQUAL_WEIGHT_AVERAGE.
        MixCombineModeFieldExperiment.applyFromPrivateFlagFile(
            File(filesDir, MixCombineModeFieldExperiment.FIELD_FLAG_FILE_NAME),
        )
        MixOutputMakeupFieldExperiment.applyFromPrivateFlagFile(
            File(filesDir, MixOutputMakeupFieldExperiment.FIELD_FLAG_FILE_NAME),
        )
        runtimeManager = TalkbackRuntimeManager(this)
        endpointTextInboundNotifier = EndpointTextInboundNotifier(this, MainScope())
        TaskProfileManager(this).ensureInitialized()
    }

    companion object {
        fun get(context: android.content.Context): TalkbackApp =
            context.applicationContext as TalkbackApp
    }
}
