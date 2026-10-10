package com.talkback.core.conference.session

import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.session.integration.Profile01ShadowMulticastReceiveSeam
import com.talkback.core.conference.session.integration.Profile01ShadowPlayoutClockSeam
import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import com.talkback.core.conference.session.integration.ShadowHook
import com.talkback.core.conference.session.integration.ShadowOutcome
import com.talkback.core.conference.session.integration.cutover.MulticastAudibleAutomaticCutover
import com.talkback.core.conference.session.integration.cutover.ReplacementCutoverRc1

/**
 * Thin coordinator delegation — no catalog/epoch/runtime state here.
 *
 * Phase A: wrapped in [MeetingProductMediaShadow] — failures become SHADOW_FAILED only;
 * never propagate to ADR-0056 Meeting path.
 */
object ConferenceSessionMediaCoordinatorDelegate {
    @Volatile
    var factPort: ConferenceSessionMediaFactPort? = null

    @Volatile
    var profile01ShadowReceiveSeam: Profile01ShadowMulticastReceiveSeam? = null

    @Volatile
    var profile01ShadowPlayoutClockSeam: Profile01ShadowPlayoutClockSeam? = null

    @Volatile
    var localModuleIdProvider: (() -> String)? = null

    fun onConferenceSessionStarted(
        sessionId: String,
        channelId: String,
        rosterEpoch: Long,
    ) {
        MeetingProductMediaShadow.runShadowHook(ShadowHook.SESSION_STARTED, sessionId) {
            if (ConferenceSessionMediaBridge.wiring == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.SESSION_STARTED,
                    sessionId,
                    null,
                    ShadowOutcome.WIRING_NOT_CONFIGURED,
                )
                return@runShadowHook
            }
            val fact = factPort?.sessionFact(sessionId, channelId, rosterEpoch)
            if (fact == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.SESSION_STARTED,
                    sessionId,
                    null,
                    ShadowOutcome.DEFERRED_NO_FACT,
                )
                return@runShadowHook
            }
            val ok = ConferenceSessionMediaBridge.startSession(fact)
            if (ok) {
                Profile01ShadowRuntimeObservability.bindActiveSession(
                    sessionId = sessionId,
                    conferenceId = channelId,
                    mediaKeyEpoch = fact.mediaKeyEpoch,
                )
                ConferenceSessionMediaBridge.wiring?.runtimeSnapshot(sessionId)?.let { snap ->
                    Profile01ShadowRuntimeObservability.logRuntimeSnapshot(
                        phase = "SHADOW_SESSION_STARTED",
                        sessionId = sessionId,
                        conferenceId = channelId,
                        mediaKeyEpoch = fact.mediaKeyEpoch,
                        snapshot = snap,
                    )
                }
                profile01ShadowReceiveSeam?.onShadowSessionStarted(sessionId)
                profile01ShadowPlayoutClockSeam?.onShadowSessionStarted(sessionId)
                maybeAttemptGaDefaultCutover(sessionId)
            }
            MeetingProductMediaShadow.observability.recordOutcome(
                ShadowHook.SESSION_STARTED,
                sessionId,
                null,
                if (ok) ShadowOutcome.APPLIED else ShadowOutcome.WIRING_REJECTED,
            )
        }
    }

    fun onConferenceMemberMediaReady(
        sessionId: String,
        moduleId: String,
    ) {
        MeetingProductMediaShadow.runShadowHook(ShadowHook.MEMBER_MEDIA_READY, sessionId, moduleId) {
            if (ConferenceSessionMediaBridge.wiring == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.MEMBER_MEDIA_READY,
                    sessionId,
                    moduleId,
                    ShadowOutcome.WIRING_NOT_CONFIGURED,
                )
                return@runShadowHook
            }
            val binding = factPort?.memberBinding(sessionId, moduleId)
            if (binding == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.MEMBER_MEDIA_READY,
                    sessionId,
                    moduleId,
                    ShadowOutcome.DEFERRED_NO_BINDING,
                )
                return@runShadowHook
            }
            val replacePending = factPort?.memberReplacePending(sessionId, moduleId) == true
            val catalogIncarnation = ConferenceSessionMediaBridge.currentIncarnation(sessionId, moduleId)
            val sessionMediaKeyEpoch = ConferenceSessionMediaBridge.currentMediaKeyEpoch(sessionId)
            val decision =
                ConferenceSessionMediaSourceSuccessionLifecycle.resolveMediaReadyDecision(
                    bindingIncarnation = binding.incarnationId,
                    catalogIncarnation = catalogIncarnation,
                    replacePending = replacePending,
                    bindingMediaKeyEpoch = binding.mediaKeyEpoch,
                    sessionMediaKeyEpoch = sessionMediaKeyEpoch ?: binding.mediaKeyEpoch,
                )
            when (decision) {
                SourceSuccessionMediaReadyDecision.DEFER_REPLACE_PENDING -> {
                    MeetingProductMediaShadow.observability.recordOutcome(
                        ShadowHook.MEMBER_MEDIA_READY,
                        sessionId,
                        moduleId,
                        ShadowOutcome.DEFERRED_REPLACE_PENDING,
                    )
                }
                SourceSuccessionMediaReadyDecision.REJECT_STALE_GENERATION -> {
                    MeetingProductMediaShadow.observability.recordOutcome(
                        ShadowHook.MEMBER_MEDIA_READY,
                        sessionId,
                        moduleId,
                        ShadowOutcome.WIRING_REJECTED,
                    )
                }
                SourceSuccessionMediaReadyDecision.ALREADY_INSTALLED -> {
                    MeetingProductMediaShadow.observability.recordOutcome(
                        ShadowHook.MEMBER_MEDIA_READY,
                        sessionId,
                        moduleId,
                        ShadowOutcome.APPLIED,
                    )
                    maybeAttemptGaDefaultCutover(sessionId)
                }
                SourceSuccessionMediaReadyDecision.INSTALL -> {
                    val ok = ConferenceSessionMediaBridge.installMember(sessionId, binding)
                    if (ok) {
                        ConferenceSessionMediaBridge.wiring?.runtimeSnapshot(sessionId)?.let { snap ->
                            Profile01ShadowRuntimeObservability.logRuntimeSnapshot(
                                phase = "SHADOW_MEMBER_INSTALLED",
                                sessionId = sessionId,
                                conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
                                mediaKeyEpoch = binding.mediaKeyEpoch,
                                snapshot = snap,
                                extra = Profile01ShadowRuntimeObservability.memberBindingFields(binding),
                            )
                        }
                        maybeAttemptGaDefaultCutover(sessionId)
                    }
                    MeetingProductMediaShadow.observability.recordOutcome(
                        ShadowHook.MEMBER_MEDIA_READY,
                        sessionId,
                        moduleId,
                        if (ok) ShadowOutcome.APPLIED else ShadowOutcome.WIRING_REJECTED,
                    )
                }
            }
        }
    }

    fun onConferenceMemberRemoved(
        sessionId: String,
        moduleId: String,
    ) {
        MeetingProductMediaShadow.runShadowHook(ShadowHook.MEMBER_REMOVED, sessionId, moduleId) {
            if (ConferenceSessionMediaBridge.wiring == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.MEMBER_REMOVED,
                    sessionId,
                    moduleId,
                    ShadowOutcome.WIRING_NOT_CONFIGURED,
                )
                return@runShadowHook
            }
            val incarnation =
                ConferenceSessionMediaBridge.currentIncarnation(sessionId, moduleId)
                    ?: factPort?.memberBinding(sessionId, moduleId)?.incarnationId
            if (incarnation == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.MEMBER_REMOVED,
                    sessionId,
                    moduleId,
                    ShadowOutcome.DEFERRED_NO_INCARNATION,
                )
                return@runShadowHook
            }
            val ok = ConferenceSessionMediaBridge.removeMember(sessionId, moduleId, incarnation)
            MeetingProductMediaShadow.observability.recordOutcome(
                ShadowHook.MEMBER_REMOVED,
                sessionId,
                moduleId,
                if (ok) ShadowOutcome.APPLIED else ShadowOutcome.WIRING_REJECTED,
            )
        }
    }

    fun onConferenceMemberReplaced(
        sessionId: String,
        moduleId: String,
    ) {
        MeetingProductMediaShadow.runShadowHook(ShadowHook.MEMBER_REPLACED, sessionId, moduleId) {
            if (ConferenceSessionMediaBridge.wiring == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.MEMBER_REPLACED,
                    sessionId,
                    moduleId,
                    ShadowOutcome.WIRING_NOT_CONFIGURED,
                )
                return@runShadowHook
            }
            val pair = factPort?.memberReplaceBinding(sessionId, moduleId)
            if (pair == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.MEMBER_REPLACED,
                    sessionId,
                    moduleId,
                    ShadowOutcome.DEFERRED_NO_REPLACE_PAIR,
                )
                return@runShadowHook
            }
            val ok = ConferenceSessionMediaBridge.replaceMember(sessionId, pair.first, pair.second)
            if (ok) {
                Profile01ShadowRuntimeObservability.logPhase(
                    phase = "SHADOW_MEMBER_REPLACED",
                    sessionId = sessionId,
                    moduleId = moduleId,
                    fields =
                        mapOf(
                            "oldSourceGeneration" to pair.first.incarnationId.toString(),
                            "newSourceGeneration" to pair.second.incarnationId.toString(),
                            "oldSsrc" to pair.first.ssrc.toString(),
                            "newSsrc" to pair.second.ssrc.toString(),
                            "outcome" to "APPLIED",
                        ),
                )
            }
            MeetingProductMediaShadow.observability.recordOutcome(
                ShadowHook.MEMBER_REPLACED,
                sessionId,
                moduleId,
                if (ok) ShadowOutcome.APPLIED else ShadowOutcome.WIRING_REJECTED,
            )
        }
    }

    /** Fence ingress at remote/local hangup before coordinator drain work (idempotent). */
    fun beginSessionTeardown(sessionId: String) {
        ConferenceSessionMediaBridge.beginSessionTeardown(sessionId)
    }

    /**
     * Meeting mute/unmute — multicast B-layer RX playout ingress recovery (no AudioTrack fence).
     */
    fun onConferenceCallMuteChanged(
        sessionId: String,
        muted: Boolean,
    ) {
        if (!MeetingProductMediaShadow.enabled) return
        try {
            ConferenceSessionMediaBridge.wiring?.onConferenceCallMuteChanged(sessionId, muted)
        } catch (t: Throwable) {
            Profile01ShadowRuntimeObservability.logShadowPlayoutFailed(
                sessionId = sessionId,
                reason = t.javaClass.simpleName,
                detail = "conference_mute_changed muted=$muted ${t.message}",
            )
        }
    }

    fun onConferenceSessionStopped(sessionId: String) {
        MulticastAudibleAutomaticCutover.onSessionStopped(sessionId)
        ReplacementCutoverRc1.onSessionTeardown(sessionId)
        MeetingProductMediaShadow.runShadowHook(ShadowHook.SESSION_STOPPED, sessionId) {
            if (ConferenceSessionMediaBridge.wiring == null) {
                MeetingProductMediaShadow.observability.recordOutcome(
                    ShadowHook.SESSION_STOPPED,
                    sessionId,
                    null,
                    ShadowOutcome.WIRING_NOT_CONFIGURED,
                )
                return@runShadowHook
            }
            ConferenceSessionMediaBridge.beginSessionTeardown(sessionId)
            profile01ShadowReceiveSeam?.onShadowSessionStopping(sessionId)
            profile01ShadowPlayoutClockSeam?.onShadowSessionStopping(sessionId)
            val ok = ConferenceSessionMediaBridge.stopSession(sessionId)
            MeetingProductMediaShadow.observability.recordOutcome(
                ShadowHook.SESSION_STOPPED,
                sessionId,
                null,
                if (ok) ShadowOutcome.APPLIED else ShadowOutcome.DEFERRED_NO_GENERATION,
            )
        }
    }

    private fun maybeAttemptGaDefaultCutover(sessionId: String) {
        val localModuleId = localModuleIdProvider?.invoke() ?: return
        // Remote MEMBER_MEDIA_READY / install is a later readiness hook: catalog
        // REMOTE_MULTICAST_RX_SOURCE is evaluated as local, not as the remote id.
        MulticastAudibleAutomaticCutover.maybeAttempt(sessionId, localModuleId)
    }
}
