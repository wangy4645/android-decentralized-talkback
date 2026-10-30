package com.talkback.core.session

/**
 * Phase 1b-7 harness — deterministic topology publish → lifecycle events → media execution steps.
 * Test-only orchestration; not production runtime.
 */
class MediaEdgeLifecycleHarness {
    private val topologyAuthority = ConferenceTopologyAuthority()
    private var previousSnapshot: ConferenceTopologySnapshot? = null
    private val topologyEvents = mutableListOf<TopologyMediaEdgeLifecycleEvent>()
    private val mediaStates = linkedMapOf<String, MediaExecutionLifecycleState>()
    private val mediaEvents = mutableListOf<MediaExecutionLifecycleEvent>()

    val currentSnapshot: ConferenceTopologySnapshot?
        get() = topologyAuthority.currentSnapshot(conferenceId)

    var conferenceId: String = "conf-lifecycle"
        private set

    fun publishAnchor(input: AnchorAdmissionInput): ConferenceTopologyAuthority.PublishResult {
        conferenceId = input.conferenceId
        val result = topologyAuthority.publishTopologyProjection(input)
        if (result is ConferenceTopologyAuthority.PublishResult.Published) {
            recordTopologyTransition(previousSnapshot, result.snapshot)
            previousSnapshot = result.snapshot
        }
        return result
    }

    fun publishRosterChange(members: List<String>): ConferenceTopologyAuthority.PublishResult {
        val result = topologyAuthority.publishRosterEdgeChange(conferenceId, members)
        if (result is ConferenceTopologyAuthority.PublishResult.Published) {
            recordTopologyTransition(previousSnapshot, result.snapshot)
            previousSnapshot = result.snapshot
        }
        return result
    }

    fun publishFailover(newAnchorId: String): ConferenceTopologyAuthority.PublishResult {
        val result = topologyAuthority.publishAnchorFailover(conferenceId, newAnchorId)
        if (result is ConferenceTopologyAuthority.PublishResult.Published) {
            recordTopologyTransition(previousSnapshot, result.snapshot)
            previousSnapshot = result.snapshot
        }
        return result
    }

    fun activateMedia(ref: TopologyMediaEdgeRef): MediaExecutionTransitionResult =
        applyMediaTransition(ref, MediaExecutionTransition.ACTIVATE)

    fun drainMedia(ref: TopologyMediaEdgeRef): MediaExecutionTransitionResult =
        applyMediaTransition(ref, MediaExecutionTransition.BEGIN_DRAIN)

    fun removeMedia(ref: TopologyMediaEdgeRef): MediaExecutionTransitionResult =
        applyMediaTransition(ref, MediaExecutionTransition.COMPLETE_REMOVE)

    fun mediaState(ref: TopologyMediaEdgeRef): MediaExecutionLifecycleState? =
        mediaStates[ref.mediaEdgeId]

    private fun recordTopologyTransition(
        previous: ConferenceTopologySnapshot?,
        next: ConferenceTopologySnapshot
    ) {
        if (previous != null) {
            when (val validity = MediaEdgeLifecycleContract.validateGenerationOnEdgeSetChange(previous, next)) {
                is SnapshotValidity.Invalid -> error(validity.reason)
                SnapshotValidity.Valid -> Unit
            }
        }
        val diff = MediaEdgeLifecycleContract.diffTopologySnapshots(previous, next)
        topologyEvents.addAll(diff)
        diff.filterIsInstance<TopologyMediaEdgeLifecycleEvent.Invalidated>().forEach { invalidated ->
            val state = mediaStates[invalidated.ref.mediaEdgeId]
            if (state == MediaExecutionLifecycleState.ACTIVE) {
                drainMedia(invalidated.ref)
                removeMedia(invalidated.ref)
            }
        }
    }

    private fun applyMediaTransition(
        ref: TopologyMediaEdgeRef,
        transition: MediaExecutionTransition
    ): MediaExecutionTransitionResult {
        val current = mediaStates[ref.mediaEdgeId] ?: MediaExecutionLifecycleState.ADMITTED
        val result = MediaEdgeLifecycleContract.transitionMediaExecution(current, transition, ref)
        if (result is MediaExecutionTransitionResult.Transitioned) {
            mediaStates[ref.mediaEdgeId] = result.to
            mediaEvents += result.event
        }
        return result
    }

    fun topologyLifecycleEvents(): List<TopologyMediaEdgeLifecycleEvent> =
        topologyEvents.toList()

    fun mediaLifecycleEvents(): List<MediaExecutionLifecycleEvent> =
        mediaEvents.toList()
}
