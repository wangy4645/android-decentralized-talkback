package com.talkback.core.media

import com.talkback.core.util.TalkbackLog
import com.talkback.core.webrtc.FactoryHit
import com.talkback.core.webrtc.MediaBearerScope

object MediaObservabilityLog {
    fun mediaLifecycle(
        moduleId: String,
        scope: MediaBearerScope,
        lifecycle: MediaLifecycle,
        generation: Long,
        iceState: String
    ) {
        TalkbackLog.i(
            "MEDIA_LIFECYCLE module=$moduleId scope=$scope lifecycle=$lifecycle " +
                "generation=$generation ice=$iceState"
        )
    }

    fun mediaSessionReuse(
        moduleId: String,
        previousScope: MediaBearerScope,
        requestedScope: MediaBearerScope,
        generation: Long
    ) {
        TalkbackLog.i(
            "MEDIA_SESSION_REUSE=1 module=$moduleId previousScope=$previousScope " +
                "requestedScope=$requestedScope generation=$generation"
        )
    }

    fun mediaBarrierComplete(moduleCount: Int, unresolvedCount: Int, channelId: String? = null) {
        val channelSuffix = channelId?.let { " ch=$it" }.orEmpty()
        TalkbackLog.i(
            "MEDIA_BARRIER_COMPLETE$channelSuffix modules=$moduleCount unresolved=$unresolvedCount " +
                "MEDIA_SESSION_REUSE=0"
        )
    }

    fun mediaReleaseScheduled(moduleId: String, origin: String) {
        TalkbackLog.i("MEDIA_RELEASE_SCHEDULED module=$moduleId origin=$origin")
    }

    fun mediaReleaseRequested(moduleId: String, origin: String) {
        TalkbackLog.i("MEDIA_RELEASE_REQUESTED module=$moduleId origin=$origin")
    }

    fun hangupBootstrapDeferred(channelId: String, origin: String) {
        TalkbackLog.i("HANGUP_BOOTSTRAP_DEFERRED ch=$channelId origin=$origin")
    }

    fun releaseEnter(moduleId: String) {
        TalkbackLog.i("RELEASE_ENTER module=$moduleId")
    }

    fun pcCloseEnter(moduleId: String) {
        TalkbackLog.i("PC_CLOSE_ENTER module=$moduleId")
    }

    fun pcCloseExit(moduleId: String) {
        TalkbackLog.i("PC_CLOSE_EXIT module=$moduleId")
    }

    fun pcCloseSkipped(moduleId: String, reason: String) {
        TalkbackLog.i("PC_CLOSE_SKIPPED module=$moduleId reason=$reason")
    }

    fun mediaPendingDrain(moduleId: String, items: List<Pair<MediaBearerScope, String>>) {
        val encoded = items.mapIndexed { index, item ->
            "$index:${item.first}:${item.second}"
        }.joinToString(",")
        TalkbackLog.i(
            "MEDIA_PENDING_DRAIN module=$moduleId count=${items.size} items=$encoded"
        )
    }

    fun sharedFactoryReleaseEnter(moduleId: String) {
        TalkbackLog.i("SHARED_FACTORY_RELEASE_ENTER module=$moduleId")
    }

    fun sharedFactoryReleaseExit(moduleId: String, stage: String = "teardown") {
        TalkbackLog.i("SHARED_FACTORY_RELEASE_EXIT module=$moduleId stage=$stage")
    }

    fun mediaReleased(moduleId: String, origin: String) {
        TalkbackLog.i("MEDIA_RELEASED module=$moduleId origin=$origin")
        TalkbackLog.i("MEDIA_RELEASE_COMPLETE module=$moduleId origin=$origin")
    }

    fun mediaReleaseFailed(moduleId: String, origin: String, cause: String? = null) {
        val suffix = cause?.let { " cause=$it" }.orEmpty()
        TalkbackLog.i("MEDIA_RELEASE_FAILED module=$moduleId origin=$origin$suffix")
    }

    fun mediaReleaseLateCompletion(moduleId: String, origin: String) {
        TalkbackLog.i("MEDIA_RELEASE_LATE_COMPLETION module=$moduleId origin=$origin")
    }

    fun mediaProvisionRequested(moduleId: String, scope: MediaBearerScope, origin: String) {
        TalkbackLog.i(
            "MEDIA_PROVISION_REQUESTED module=$moduleId scope=$scope origin=$origin"
        )
    }

    fun mediaProvisioned(
        moduleId: String,
        scope: MediaBearerScope,
        origin: String,
        generation: Long? = null,
        pcHash: Int? = null,
        factoryHit: FactoryHit? = null
    ) {
        val genField = generation?.let { " generation=$it" }.orEmpty()
        val hashField = pcHash?.let { " pcHash=$it" }.orEmpty()
        val hitField = factoryHit?.let { " factoryHit=$it" }.orEmpty()
        TalkbackLog.i(
            "MEDIA_PROVISIONED module=$moduleId scope=$scope origin=$origin$genField$hashField$hitField"
        )
    }

    fun mediaProvisionFailed(moduleId: String, scope: MediaBearerScope, origin: String) {
        TalkbackLog.i(
            "MEDIA_PROVISION_FAILED module=$moduleId scope=$scope origin=$origin"
        )
    }

    fun mediaReleaseDeferredCreate(
        moduleId: String,
        requestedScope: MediaBearerScope,
        intent: EngineRequestIntent = EngineRequestIntent.DEFAULT,
        sessionId: String? = null,
    ) {
        val intentField =
            if (intent == EngineRequestIntent.CREATE_CONTINUATION) {
                " intent=CREATE_CONTINUATION session=$sessionId"
            } else {
                ""
            }
        TalkbackLog.i(
            "MEDIA_RELEASE_DEFERRED_CREATE module=$moduleId requestedScope=$requestedScope$intentField"
        )
    }

    fun mediaCreateContinuationDrain(moduleId: String, count: Int, items: String) {
        TalkbackLog.i(
            "MEDIA_CREATE_CONTINUATION_DRAIN module=$moduleId count=$count items=$items"
        )
    }

    fun mediaCreateContinuationResumed(moduleId: String, sessionId: String?) {
        TalkbackLog.i(
            "MEDIA_CREATE_CONTINUATION_RESUMED module=$moduleId session=$sessionId"
        )
    }

    fun mediaCreateContinuationDiscarded(moduleId: String, sessionId: String?, reason: String) {
        TalkbackLog.i(
            "MEDIA_CREATE_CONTINUATION_DISCARDED module=$moduleId session=$sessionId reason=$reason"
        )
    }

    fun engineOwnershipDeferred(
        moduleId: String,
        owner: EngineOwnershipGate.Owner,
        state: EngineOwnershipGate.State,
        origin: String
    ) {
        TalkbackLog.i(
            "ENGINE_OWNERSHIP_DEFERRED module=$moduleId owner=$owner state=$state origin=$origin"
        )
    }
}
