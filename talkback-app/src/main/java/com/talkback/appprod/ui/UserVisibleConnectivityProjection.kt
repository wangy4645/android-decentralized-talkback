package com.talkback.appprod.ui

/**
 * User-visible connectivity projection (ADR-0034 / INV-PRES-001..009).
 *
 * Pure mapping only: no timers, caches, mutations, admission, or recovery ownership.
 */
object UserVisibleConnectivityProjection {

    enum class MediaUsability {
        MEDIA_OK,
        MEDIA_UNAVAILABLE
    }

    enum class ControlSyncState {
        STABLE,
        SYNCING,
        DEGRADED
    }

    enum class UserVisibleConnectivityState {
        CONNECTED,
        SYNCING,
        DEGRADED,
        RECONNECTING
    }

    data class MeetingConnectivitySummary(
        val state: UserVisibleConnectivityState,
        val peerIds: List<String>
    )

    data class ConnectivityAxes(
        val media: MediaUsability,
        val control: ControlSyncState
    )

    /**
     * Dual-axis map (Q6=F-4). Hard veto: [MediaUsability.MEDIA_OK] never yields RECONNECTING.
     */
    fun project(media: MediaUsability, control: ControlSyncState): UserVisibleConnectivityState =
        when (media) {
            MediaUsability.MEDIA_OK -> when (control) {
                ControlSyncState.STABLE -> UserVisibleConnectivityState.CONNECTED
                ControlSyncState.SYNCING -> UserVisibleConnectivityState.SYNCING
                ControlSyncState.DEGRADED -> UserVisibleConnectivityState.DEGRADED
            }
            MediaUsability.MEDIA_UNAVAILABLE -> when (control) {
                ControlSyncState.STABLE -> UserVisibleConnectivityState.DEGRADED
                ControlSyncState.SYNCING,
                ControlSyncState.DEGRADED -> UserVisibleConnectivityState.RECONNECTING
            }
        }

    fun project(axes: ConnectivityAxes): UserVisibleConnectivityState =
        project(axes.media, axes.control)

    /**
     * Fact-adapter boundary: derive coarse axes from local presentation inputs.
     * Lifecycle booleans may inform [ControlSyncState] only — never become UI states themselves.
     */
    fun deriveAxes(
        receivePathLive: Boolean,
        mediaEverLive: Boolean,
        recovering: Boolean,
        mediaUnavailable: Boolean,
        controlDegraded: Boolean = false,
        controlSyncPending: Boolean = false
    ): ConnectivityAxes {
        val media =
            if (receivePathLive && !mediaUnavailable) {
                MediaUsability.MEDIA_OK
            } else {
                MediaUsability.MEDIA_UNAVAILABLE
            }
        val control = when {
            controlDegraded -> ControlSyncState.DEGRADED
            recovering ||
                controlSyncPending ||
                mediaUnavailable ||
                (mediaEverLive && !receivePathLive) -> ControlSyncState.SYNCING
            else -> ControlSyncState.STABLE
        }
        return ConnectivityAxes(media, control)
    }

    /** Initial-join path is membership/presence, not connectivity UX. */
    fun isInitialJoinPath(
        receivePathLive: Boolean,
        mediaEverLive: Boolean,
        recovering: Boolean,
        mediaUnavailable: Boolean
    ): Boolean =
        !receivePathLive && !mediaEverLive && !recovering && !mediaUnavailable

    /**
     * Non-escalating meeting aggregation (INV-PRES-008).
     * Severity: RECONNECTING > DEGRADED > SYNCING > CONNECTED.
     * Equal severity may include multiple peer ids; count never outranks severity.
     */
    fun aggregateMeetingConnectivity(
        peers: List<Pair<String, UserVisibleConnectivityState>>
    ): MeetingConnectivitySummary? {
        if (peers.isEmpty()) return null
        val maxSeverity = peers.maxOf { severity(it.second) }
        if (maxSeverity == severity(UserVisibleConnectivityState.CONNECTED)) return null
        val winners = peers.filter { severity(it.second) == maxSeverity }
        return MeetingConnectivitySummary(
            state = winners.first().second,
            peerIds = winners.map { it.first }
        )
    }

    fun formatMeetingHint(summary: MeetingConnectivitySummary?): String? {
        if (summary == null) return null
        val label = when (summary.state) {
            UserVisibleConnectivityState.RECONNECTING -> "reconnecting"
            UserVisibleConnectivityState.DEGRADED -> "degraded"
            UserVisibleConnectivityState.SYNCING -> "syncing"
            UserVisibleConnectivityState.CONNECTED -> return null
        }
        return when (summary.peerIds.size) {
            0 -> null
            1 -> "${summary.peerIds.single()} $label..."
            else -> "${summary.peerIds.size} members $label"
        }
    }

    fun severity(state: UserVisibleConnectivityState): Int =
        when (state) {
            UserVisibleConnectivityState.RECONNECTING -> 3
            UserVisibleConnectivityState.DEGRADED -> 2
            UserVisibleConnectivityState.SYNCING -> 1
            UserVisibleConnectivityState.CONNECTED -> 0
        }
}
