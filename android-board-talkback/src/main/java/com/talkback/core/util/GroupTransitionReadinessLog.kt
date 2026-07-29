package com.talkback.core.util



import com.talkback.core.session.TalkbackSession

import com.talkback.governance.transition.TransitionRecord

import com.talkback.governance.transition.TransitionTerminalState

import com.talkback.governance.transition.TransitionTrigger

import java.util.UUID

import java.util.concurrent.ConcurrentHashMap

import java.util.concurrent.atomic.AtomicInteger



/**

 * P0-a / P2-0 observation: meeting-end -> GROUP transition readiness (ADR-0022, ADR-0027).

 *

 * Read-only telemetry. Does not gate PTT, mesh, floor, or playback.

 *

 * Grep: `GROUP_TRANSITION_READINESS_SNAPSHOT`, `MEETING_END_BEGIN`, `BOOTSTRAP_ATTEMPT`,

 * `TRANSITION_TERMINAL_READY`, `CONVERGENCE_WINDOW_BEGIN`, `PRIMARY_RESOLVE`, `GROUP_SESSION_CREATE`,

 * `TRANSITION_TERMINAL_READY`, `CONVERGENCE_WINDOW_BEGIN`, `PRIMARY_RESOLVE`, `GROUP_SESSION_CREATE`,

 * `GROUP_SESSION_RECREATE`, `CANONICAL_DECISION`, `CANONICAL_DECISION_APPLIED`, `LOCAL_TERMINAL_SELF_LEASE`

 */

object GroupTransitionReadinessLog {

    /** P2-0.5: observability-only classification for TRANSITION_TERMINAL_READY. */
    enum class TerminalAuthority {
        CANONICAL,
        CANONICAL_APPLIED,
        LOCAL_OPERATIONAL,
        LOCAL_SELF_LEASE
    }



    data class SessionIdentityObservation(

        val sessionLineageId: String?,

        val sessionTraceId: String?,

        val parentTraceId: String?,

        val localSessionId: String?,

        val initiatorModuleId: String?,

        val anchorModuleId: String?,

        val floorAuthorityModuleId: String?,

        val resolvedBootstrapPrimaryModuleId: String?,

        val membershipEpoch: Long?,

        val baselineMembers: List<String>,

        val orphanBelief: Boolean,

        val sessionRole: String

    )



    data class ReadinessObservation(

        val membershipReady: Boolean,

        val transmitReady: Boolean,

        val terminalReady: Boolean,

        val joinedMembers: List<String>,

        val activeMembers: List<String>,

        val transmitRequiredPeers: Set<String>,

        val transmitConnectedPeers: Set<String>,

        val peerIceStates: Map<String, String>

    )



    data class ReceiveCapabilityObservation(

        val sampled: Boolean,

        val floorHolder: String?,

        val holderAudioReachable: Boolean?,

        val holderMediaConnected: Boolean?,

        val failureReason: String?

    )



    data class TransitionObservation(

        val state: String,

        val trigger: String?,

        val transitionId: String?,

        val startedAtMs: Long?,

        val terminalReady: Boolean

    )



    data class BootstrapObservation(

        val waitingForPrimary: Boolean,

        val resolvedPrimary: String?,

        val bootstrapAttemptCount: Int,

        val meshRecoveryState: String?

    )



    data class Snapshot(

        val channelId: String,

        val moduleId: String,

        val timestampMs: Long,

        val transition: TransitionObservation,

        val session: SessionIdentityObservation,

        val bootstrap: BootstrapObservation,

        val readiness: ReadinessObservation,

        val receive: ReceiveCapabilityObservation

    )



    private data class ChannelMetrics(

        var meetingEndBeginMs: Long? = null,

        var transitionTerminalMs: Long? = null,

        var sessionLineageId: String? = null,

        var baselineMembers: List<String> = emptyList(),

        var lastObservedTraceId: String? = null,

        var parentTraceIdForCurrent: String? = null,

        var lastObservedPrimary: String? = null,

        var lastObservedJoinedMembers: Set<String> = emptySet(),

        var waitingForPrimarySinceMs: Long? = null,

        val bootstrapAttemptCount: AtomicInteger = AtomicInteger(0),

        val primaryResolveCount: AtomicInteger = AtomicInteger(0),

        val primaryResolveNoMutationCount: AtomicInteger = AtomicInteger(0),

        val primaryChangeCount: AtomicInteger = AtomicInteger(0),

        val primaryResolveMutationCount: AtomicInteger = AtomicInteger(0),

        val sessionCreateCount: AtomicInteger = AtomicInteger(0),

        val sessionRecreateCount: AtomicInteger = AtomicInteger(0),

        val sessionRecreateByPrimaryChangeCount: AtomicInteger = AtomicInteger(0),

        var orphanBeliefSinceMs: Long? = null,

        var lastOrphanBelief: Boolean = false,

        var lastCanonicalDecisionId: String? = null,

        var canonicalDecisionEmitted: Boolean = false

    )



    private val metricsByChannel = ConcurrentHashMap<String, ChannelMetrics>()



    fun onMeetingEndBegin(

        channelId: String,

        moduleId: String,

        reason: String,

        membershipBaseline: List<String>,

        snapshot: Snapshot

    ) {

        val metrics = metricsFor(channelId)

        val lineageId = newLineageId()

        metrics.meetingEndBeginMs = snapshot.timestampMs

        metrics.transitionTerminalMs = null

        metrics.sessionLineageId = lineageId

        metrics.baselineMembers = membershipBaseline.ifEmpty {
            snapshot.readiness.joinedMembers.ifEmpty { snapshot.readiness.activeMembers }
        }.sorted()

        metrics.lastObservedTraceId = snapshot.session.sessionTraceId

        metrics.parentTraceIdForCurrent = null

        metrics.lastObservedPrimary = snapshot.session.resolvedBootstrapPrimaryModuleId

        metrics.lastObservedJoinedMembers = snapshot.readiness.joinedMembers.toSet()

        metrics.waitingForPrimarySinceMs = null

        metrics.bootstrapAttemptCount.set(0)

        metrics.primaryResolveCount.set(0)

        metrics.primaryResolveNoMutationCount.set(0)

        metrics.primaryChangeCount.set(0)

        metrics.primaryResolveMutationCount.set(0)

        metrics.sessionCreateCount.set(0)

        metrics.sessionRecreateCount.set(0)

        metrics.sessionRecreateByPrimaryChangeCount.set(0)

        metrics.orphanBeliefSinceMs = null

        metrics.lastOrphanBelief = false

        metrics.lastCanonicalDecisionId = null

        metrics.canonicalDecisionEmitted = false

        TalkbackLog.i(

            buildString {

                append("CONVERGENCE_WINDOW_BEGIN ")

                appendKv("ch", channelId)

                appendKv("moduleId", moduleId)

                appendKv("sessionLineageId", lineageId)

                appendKv("baselineMembers", metrics.baselineMembers.joinToString(","))

                appendKv("reason", reason)

                appendKv("tsMs", snapshot.timestampMs)

            }

        )

        TalkbackLog.i(

            buildString {

                append("MEETING_END_BEGIN ")

                appendKv("ch", channelId)

                appendKv("moduleId", moduleId)

                appendKv("sessionLineageId", lineageId)

                appendKv("reason", reason)

                appendKv("tsMs", snapshot.timestampMs)

                appendSessionFields(snapshot.session)

            }

        )

        observeSessionTraceIfChanged(

            channelId = channelId,

            moduleId = moduleId,

            snapshot = snapshot,

            meshRecoveryState = snapshot.bootstrap.meshRecoveryState,

            forceReason = "meeting_end_begin"

        )

        emitSnapshot(snapshot)

    }



    fun onBootstrapAttempt(

        channelId: String,

        moduleId: String,

        attemptId: Int,

        resolvedPrimary: String?,

        waitingForPrimary: Boolean,

        snapshot: Snapshot

    ) {

        val metrics = metricsFor(channelId)

        metrics.bootstrapAttemptCount.incrementAndGet()

        if (waitingForPrimary) {

            if (metrics.waitingForPrimarySinceMs == null) {

                metrics.waitingForPrimarySinceMs = snapshot.timestampMs

            }

        } else {

            metrics.waitingForPrimarySinceMs = null

        }

        val attemptReason = snapshot.bootstrap.meshRecoveryState ?: "unknown"

        TalkbackLog.i(

            buildString {

                append("BOOTSTRAP_ATTEMPT ")

                appendKv("ch", channelId)

                appendKv("moduleId", moduleId)

                appendKv("attemptId", attemptId)

                appendKv("resolvedBootstrapPrimaryModuleId", resolvedPrimary)

                appendKv("waitingForPrimary", waitingForPrimary)

                appendKv("bootstrapAttemptReason", attemptReason)

                appendKv("sessionLineageId", metrics.sessionLineageId)

                appendKv("tsMs", snapshot.timestampMs)

            }

        )

        trackOrphanBelief(metrics, snapshot.session.orphanBelief, snapshot.timestampMs)

        observeSessionTraceIfChanged(

            channelId = channelId,

            moduleId = moduleId,

            snapshot = snapshot,

            meshRecoveryState = attemptReason

        )

        emitSnapshot(snapshot)

    }



    fun onPrimaryResolve(

        channelId: String,

        moduleId: String,

        resolvedPrimary: String?,

        snapshot: Snapshot

    ) {

        val metrics = metricsFor(channelId)

        metrics.primaryResolveCount.incrementAndGet()

        val previousPrimary = metrics.lastObservedPrimary

        val primaryChanged = previousPrimary != null &&

            resolvedPrimary != null &&

            previousPrimary != resolvedPrimary

        val traceChanged = metrics.lastObservedTraceId != snapshot.session.sessionTraceId

        val membersChanged = metrics.lastObservedJoinedMembers != snapshot.readiness.joinedMembers.toSet()

        val sessionMutated = traceChanged || membersChanged

        when {

            primaryChanged -> metrics.primaryChangeCount.incrementAndGet()

            !sessionMutated -> metrics.primaryResolveNoMutationCount.incrementAndGet()

            else -> metrics.primaryResolveMutationCount.incrementAndGet()

        }

        TalkbackLog.i(

            buildString {

                append("PRIMARY_RESOLVE ")

                appendKv("ch", channelId)

                appendKv("moduleId", moduleId)

                appendKv("resolvedBootstrapPrimaryModuleId", resolvedPrimary)

                appendKv("previousBootstrapPrimaryModuleId", previousPrimary)

                appendKv("primaryChanged", primaryChanged)

                appendKv("sessionMutated", sessionMutated)

                appendKv("traceChanged", traceChanged)

                appendKv("membersChanged", membersChanged)

                appendKv("primaryResolveCount", metrics.primaryResolveCount.get())

                appendKv("primaryChangeCount", metrics.primaryChangeCount.get())

                appendKv("primaryResolveNoMutationCount", metrics.primaryResolveNoMutationCount.get())

                appendKv("primaryResolveMutationCount", metrics.primaryResolveMutationCount.get())

                appendKv("sessionLineageId", metrics.sessionLineageId)

                appendKv("tsMs", snapshot.timestampMs)

            }

        )

        metrics.lastObservedPrimary = resolvedPrimary

        trackOrphanBelief(metrics, snapshot.session.orphanBelief, snapshot.timestampMs)

        observeSessionTraceIfChanged(

            channelId = channelId,

            moduleId = moduleId,

            snapshot = snapshot,

            meshRecoveryState = "primary_resolve",

            recreateByPrimaryChange = primaryChanged

        )

        emitSnapshot(snapshot, reason = "primary_resolve resolved=$resolvedPrimary")

    }



    fun onReadinessChanged(snapshot: Snapshot) {

        val metrics = metricsFor(snapshot.channelId)

        trackOrphanBelief(metrics, snapshot.session.orphanBelief, snapshot.timestampMs)

        observeSessionTraceIfChanged(

            channelId = snapshot.channelId,

            moduleId = snapshot.moduleId,

            snapshot = snapshot,

            meshRecoveryState = snapshot.bootstrap.meshRecoveryState

        )

        emitSnapshot(snapshot, reason = "readiness_change")

    }



    fun onTransitionTerminalReady(

        channelId: String,

        moduleId: String,

        record: TransitionRecord,

        snapshot: Snapshot

    ) {

        if (record.trigger != TransitionTrigger.MEETING_END) return

        if (record.terminal != TransitionTerminalState.READY) return

        val metrics = metricsFor(channelId)

        metrics.transitionTerminalMs = snapshot.timestampMs

        val durationMs = metrics.meetingEndBeginMs?.let { snapshot.timestampMs - it }

        val authority = classifyAndEmitTerminalAuthority(

            channelId = channelId,

            moduleId = moduleId,

            snapshot = snapshot,

            decision = "READY"

        )

        TalkbackLog.i(

            buildString {

                append("TRANSITION_TERMINAL_READY ")

                appendKv("ch", channelId)

                appendKv("moduleId", moduleId)

                appendKv("transitionId", record.id.raw)

                appendKv("trigger", record.trigger.name)

                appendKv("terminal", record.terminal?.name)

                appendKv("terminalAuthority", authority.name)

                appendKv("canonicalDecisionId", metrics.lastCanonicalDecisionId)

                appendKv("durationMs", durationMs)

                appendKv("sessionLineageId", metrics.sessionLineageId)

                appendKv("tsMs", snapshot.timestampMs)

            }

        )

        trackOrphanBelief(metrics, snapshot.session.orphanBelief, snapshot.timestampMs)

        observeSessionTraceIfChanged(

            channelId = channelId,

            moduleId = moduleId,

            snapshot = snapshot,

            meshRecoveryState = "transition_terminal_ready"

        )

        emitSnapshot(snapshot, reason = "transition_terminal_ready")

    }



    /**

     * P2-0.5: lease-expired self-degrade observation (G-P2-TERM-1 legal fallback).

     * Callable when membership lease expires; does not gate runtime.

     */

    fun onLocalTerminalSelfLease(

        channelId: String,

        moduleId: String,

        lineageId: String?,

        snapshot: Snapshot,

        reason: String = "PRESENCE_LEASE_EXPIRED"

    ) {

        TalkbackLog.i(

            buildString {

                append("LOCAL_TERMINAL_SELF_LEASE ")

                appendKv("ch", channelId)

                appendKv("moduleId", moduleId)

                appendKv("lineageId", lineageId ?: metricsFor(channelId).sessionLineageId)

                appendKv("reason", reason)

                appendKv("terminalAuthority", TerminalAuthority.LOCAL_SELF_LEASE.name)

                appendKv("tsMs", snapshot.timestampMs)

            }

        )

        emitSnapshot(snapshot, reason = "local_terminal_self_lease")

    }



    /**

     * P2-0.5: primary canonical decision observation anchor.

     * Observability only — does not gate transition or PTT.

     */

    fun onCanonicalDecision(

        channelId: String,

        moduleId: String,

        snapshot: Snapshot,

        decision: String,

        decisionId: String = newDecisionId()

    ): String {

        val metrics = metricsFor(channelId)

        metrics.lastCanonicalDecisionId = decisionId

        metrics.canonicalDecisionEmitted = true

        val session = snapshot.session

        TalkbackLog.i(

            buildString {

                append("CANONICAL_DECISION ")

                appendKv("ch", channelId)

                appendKv("moduleId", moduleId)

                appendKv("decisionId", decisionId)

                appendKv("lineageId", metrics.sessionLineageId)

                appendKv("sessionTraceId", session.sessionTraceId)

                appendKv("membershipEpoch", session.membershipEpoch)

                appendKv("decision", decision)

                appendKv("initiatorModuleId", session.initiatorModuleId)

                appendKv("anchorModuleId", session.anchorModuleId)

                appendKv("floorAuthorityModuleId", session.floorAuthorityModuleId)

                appendKv("bootstrapPrimaryModuleId", session.resolvedBootstrapPrimaryModuleId)

                appendKv("members", snapshot.readiness.joinedMembers.joinToString(","))

                appendKv("tsMs", snapshot.timestampMs)

            }

        )

        return decisionId

    }



    /**

     * P2-0.5: participant applied canonical decision (observation).

     */

    fun onCanonicalDecisionApplied(

        channelId: String,

        moduleId: String,

        snapshot: Snapshot,

        decisionId: String?,

        source: String = "CANONICAL"

    ) {

        val metrics = metricsFor(channelId)

        TalkbackLog.i(

            buildString {

                append("CANONICAL_DECISION_APPLIED ")

                appendKv("ch", channelId)

                appendKv("moduleId", moduleId)

                appendKv("source", source)

                appendKv("decisionId", decisionId)

                appendKv("lineageId", metrics.sessionLineageId)

                appendKv("tsMs", snapshot.timestampMs)

            }

        )

    }



    /**

     * Classifies terminal authority and emits P2-0.5 markers. Pure observation.

     */

    fun classifyTerminalAuthority(

        moduleId: String,

        snapshot: Snapshot

    ): TerminalAuthority {

        val primary = snapshot.session.resolvedBootstrapPrimaryModuleId

        return when {

            moduleId == primary -> TerminalAuthority.CANONICAL

            isAdmittedToPrimaryMesh(snapshot) -> TerminalAuthority.CANONICAL_APPLIED

            else -> TerminalAuthority.LOCAL_OPERATIONAL

        }

    }



    internal fun classifyAndEmitTerminalAuthority(

        channelId: String,

        moduleId: String,

        snapshot: Snapshot,

        decision: String

    ): TerminalAuthority {

        val authority = classifyTerminalAuthority(moduleId, snapshot)

        when (authority) {

            TerminalAuthority.CANONICAL -> onCanonicalDecision(

                channelId = channelId,

                moduleId = moduleId,

                snapshot = snapshot,

                decision = decision

            )

            TerminalAuthority.CANONICAL_APPLIED -> onCanonicalDecisionApplied(

                channelId = channelId,

                moduleId = moduleId,

                snapshot = snapshot,

                decisionId = metricsFor(channelId).lastCanonicalDecisionId,

                source = "CANONICAL_INFERRED"

            )

            TerminalAuthority.LOCAL_OPERATIONAL -> Unit

            TerminalAuthority.LOCAL_SELF_LEASE -> Unit

        }

        return authority

    }



    internal fun isAdmittedToPrimaryMesh(snapshot: Snapshot): Boolean {

        val primary = snapshot.session.resolvedBootstrapPrimaryModuleId ?: return false

        val initiator = snapshot.session.initiatorModuleId ?: return false

        return snapshot.session.sessionTraceId != null && initiator == primary

    }



    fun computeOrphanBelief(

        localModuleId: String,

        groupSession: TalkbackSession?,

        resolvedBootstrapPrimaryModuleId: String?,

        primaryMeshAdmissionObserved: Boolean

    ): Boolean {

        if (groupSession == null || !groupSession.accepted) return false

        val initiator = groupSession.initiatorModuleId?.value ?: return false

        val primary = resolvedBootstrapPrimaryModuleId ?: return false

        return initiator == localModuleId &&

            primary != localModuleId &&

            !primaryMeshAdmissionObserved

    }



    fun computePrimaryMeshAdmissionObserved(

        groupSession: TalkbackSession?,

        resolvedBootstrapPrimaryModuleId: String?,

        localModuleId: String

    ): Boolean {

        if (resolvedBootstrapPrimaryModuleId == null) return false

        if (localModuleId == resolvedBootstrapPrimaryModuleId) return true

        val session = groupSession ?: return false

        if (!session.accepted) return false

        return session.initiatorModuleId?.value == resolvedBootstrapPrimaryModuleId

    }



    fun sessionRole(groupSession: TalkbackSession?, localModuleId: String): String {

        if (groupSession == null || !groupSession.accepted) return "NONE"

        val initiator = groupSession.initiatorModuleId?.value ?: return "PARTICIPANT"

        return if (initiator == localModuleId) "HOST" else "PARTICIPANT"

    }



    fun sessionLineageId(channelId: String): String? = metricsByChannel[channelId]?.sessionLineageId



    fun baselineMembers(channelId: String): List<String> =

        metricsByChannel[channelId]?.baselineMembers.orEmpty()



    fun parentTraceId(channelId: String): String? =

        metricsByChannel[channelId]?.parentTraceIdForCurrent



    internal fun resetForTest() {

        metricsByChannel.clear()

    }



    fun bootstrapAttemptCount(channelId: String): Int =

        metricsByChannel[channelId]?.bootstrapAttemptCount?.get() ?: 0



    internal fun metricsSnapshotForTest(channelId: String): Int =

        bootstrapAttemptCount(channelId)



    internal fun primaryChangeCountForTest(channelId: String): Int =

        metricsByChannel[channelId]?.primaryChangeCount?.get() ?: 0



    internal fun lastCanonicalDecisionIdForTest(channelId: String): String? =

        metricsByChannel[channelId]?.lastCanonicalDecisionId



    internal fun canonicalDecisionEmittedForTest(channelId: String): Boolean =

        metricsByChannel[channelId]?.canonicalDecisionEmitted == true



    private fun metricsFor(channelId: String): ChannelMetrics =

        metricsByChannel.computeIfAbsent(channelId) { ChannelMetrics() }



    private fun newLineageId(): String = UUID.randomUUID().toString().take(8)



    private fun newDecisionId(): String = UUID.randomUUID().toString().take(8)



    private fun observeSessionTraceIfChanged(

        channelId: String,

        moduleId: String,

        snapshot: Snapshot,

        meshRecoveryState: String?,

        forceReason: String? = null,

        recreateByPrimaryChange: Boolean = false

    ) {

        val metrics = metricsFor(channelId)

        val newTraceId = snapshot.session.sessionTraceId

        val previousTraceId = metrics.lastObservedTraceId

        if (newTraceId == null) {

            metrics.lastObservedJoinedMembers = snapshot.readiness.joinedMembers.toSet()

            return

        }

        if (newTraceId == previousTraceId) {

            metrics.lastObservedJoinedMembers = snapshot.readiness.joinedMembers.toSet()

            return

        }

        val reason = forceReason ?: meshRecoveryState ?: "trace_change"

        if (previousTraceId == null) {

            metrics.parentTraceIdForCurrent = null

            metrics.sessionCreateCount.incrementAndGet()

            TalkbackLog.i(

                buildString {

                    append("GROUP_SESSION_CREATE ")

                    appendKv("ch", channelId)

                    appendKv("moduleId", moduleId)

                    appendKv("sessionLineageId", metrics.sessionLineageId)

                    appendKv("sessionTraceId", newTraceId)

                    appendKv("membershipEpoch", snapshot.session.membershipEpoch)

                    appendKv("currentMembers", snapshot.readiness.joinedMembers.joinToString(","))

                    appendKv("reason", reason)

                    appendKv("tsMs", snapshot.timestampMs)

                }

            )

        } else {

            metrics.parentTraceIdForCurrent = previousTraceId

            metrics.sessionRecreateCount.incrementAndGet()

            if (recreateByPrimaryChange) {

                metrics.sessionRecreateByPrimaryChangeCount.incrementAndGet()

            }

            TalkbackLog.i(

                buildString {

                    append("GROUP_SESSION_RECREATE ")

                    appendKv("ch", channelId)

                    appendKv("moduleId", moduleId)

                    appendKv("sessionLineageId", metrics.sessionLineageId)

                    appendKv("oldTraceId", previousTraceId)

                    appendKv("newTraceId", newTraceId)

                    appendKv("parentTraceId", previousTraceId)

                    appendKv("membershipEpoch", snapshot.session.membershipEpoch)

                    appendKv("currentMembers", snapshot.readiness.joinedMembers.joinToString(","))

                    appendKv("baselineMembers", metrics.baselineMembers.joinToString(","))

                    appendKv("reason", reason)

                    appendKv("recreateByPrimaryChange", recreateByPrimaryChange)

                    appendKv("tsMs", snapshot.timestampMs)

                }

            )

        }

        metrics.lastObservedTraceId = newTraceId

        metrics.lastObservedJoinedMembers = snapshot.readiness.joinedMembers.toSet()

    }



    private fun trackOrphanBelief(metrics: ChannelMetrics, orphanBelief: Boolean, nowMs: Long) {

        if (orphanBelief) {

            if (metrics.orphanBeliefSinceMs == null) {

                metrics.orphanBeliefSinceMs = nowMs

            }

        } else {

            metrics.orphanBeliefSinceMs = null

        }

        metrics.lastOrphanBelief = orphanBelief

    }



    private fun emitSnapshot(snapshot: Snapshot, reason: String? = null) {

        val metrics = metricsFor(snapshot.channelId)

        val waitingDurationMs = metrics.waitingForPrimarySinceMs?.let { snapshot.timestampMs - it }

        TalkbackLog.i(

            buildString {

                append("GROUP_TRANSITION_READINESS_SNAPSHOT ")

                appendKv("ch", snapshot.channelId)

                appendKv("moduleId", snapshot.moduleId)

                appendKv("tsMs", snapshot.timestampMs)

                reason?.let { appendKv("reason", it) }

                appendKv("transitionState", snapshot.transition.state)

                appendKv("transitionTrigger", snapshot.transition.trigger)

                appendKv("transitionId", snapshot.transition.transitionId)

                appendKv("transitionStartedAtMs", snapshot.transition.startedAtMs)

                appendKv("terminalReady", snapshot.transition.terminalReady)

                appendSessionFields(snapshot.session)

                appendKv("waitingForPrimary", snapshot.bootstrap.waitingForPrimary)

                appendKv("waitingForPrimaryDurationMs", waitingDurationMs)

                appendKv("resolvedBootstrapPrimaryModuleId", snapshot.bootstrap.resolvedPrimary)

                appendKv("bootstrapAttemptCount", snapshot.bootstrap.bootstrapAttemptCount)

                appendKv("meshRecoveryState", snapshot.bootstrap.meshRecoveryState)

                appendKv("primaryResolveCount", metrics.primaryResolveCount.get())

                appendKv("primaryChangeCount", metrics.primaryChangeCount.get())

                appendKv("primaryResolveNoMutationCount", metrics.primaryResolveNoMutationCount.get())

                appendKv("sessionCreateCount", metrics.sessionCreateCount.get())

                appendKv("sessionRecreateCount", metrics.sessionRecreateCount.get())

                appendKv("sessionRecreateByPrimaryChange", metrics.sessionRecreateByPrimaryChangeCount.get())

                appendKv("membershipReady", snapshot.readiness.membershipReady)

                appendKv("transmitReady", snapshot.readiness.transmitReady)

                appendKv("joinedMembers", snapshot.readiness.joinedMembers.joinToString(","))

                appendKv("activeMembers", snapshot.readiness.activeMembers.joinToString(","))

                appendKv(

                    "transmitRequiredPeers",

                    snapshot.readiness.transmitRequiredPeers.joinToString(",")

                )

                appendKv(

                    "transmitConnectedPeers",

                    snapshot.readiness.transmitConnectedPeers.joinToString(",")

                )

                appendKv(

                    "peerIceStates",

                    snapshot.readiness.peerIceStates.entries.joinToString(";") { "${it.key}=${it.value}" }

                )

                appendKv("receiveSampled", snapshot.receive.sampled)

                if (snapshot.receive.sampled) {

                    appendKv("floorHolder", snapshot.receive.floorHolder)

                    appendKv("holderAudioReachable", snapshot.receive.holderAudioReachable)

                    appendKv("holderMediaConnected", snapshot.receive.holderMediaConnected)

                    snapshot.receive.failureReason?.let { appendKv("receiveFailureReason", it) }

                }

            }

        )

    }



    private fun StringBuilder.appendSessionFields(session: SessionIdentityObservation) {

        appendKv("sessionLineageId", session.sessionLineageId)

        appendKv("sessionTraceId", session.sessionTraceId)

        appendKv("parentTraceId", session.parentTraceId)

        appendKv("localSessionId", session.localSessionId)

        appendKv("initiatorModuleId", session.initiatorModuleId)

        appendKv("anchorModuleId", session.anchorModuleId)

        appendKv("floorAuthorityModuleId", session.floorAuthorityModuleId)

        appendKv("resolvedBootstrapPrimaryModuleId", session.resolvedBootstrapPrimaryModuleId)

        appendKv("membershipEpoch", session.membershipEpoch)

        appendKv("baselineMembers", session.baselineMembers.joinToString(","))

        appendKv("orphanBelief", session.orphanBelief)

        appendKv("sessionRole", session.sessionRole)

    }



    private fun StringBuilder.appendKv(key: String, value: Any?) {

        append(' ')

        append(key)

        append('=')

        append(value?.toString().orEmpty())

    }

}


