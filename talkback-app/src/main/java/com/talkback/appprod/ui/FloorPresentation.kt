package com.talkback.appprod.ui

/**
 * Presentation Rule #5
 *
 * Transport state MUST NOT determine Control state.
 *
 * floorOwner is derived exclusively from the Floor protocol (control plane).
 * Reachability is an independent presentation attribute (media plane), and
 * never collapses a present owner back into "nobody is speaking".
 *
 * Concretely, the Presentation layer is forbidden from doing:
 *     ice != CONNECTED  -> floorOwner = null
 *     audioLost         -> nobody speaking
 * The valid, expressible state is:
 *     Speaking(moduleId, reachable = false)  // someone holds the floor, media is down
 *
 * UI Gate (enforced by review, see PR-UI-1):
 *   - ViewModel MUST NOT read iceState / QoS snapshots / any transport state.
 *   - Reachability MUST come from a Runtime semantic API, not from ICE directly.
 *   - UI identity is the ModuleId; endpoint keys ("M03-E01") never reach the UI.
 */
sealed interface FloorPresentation {

    /** No one holds the floor on this channel. */
    object Idle : FloorPresentation

    /**
     * Someone holds the floor (control plane).
     *
     * @param moduleId UI identity of the floor holder (never an endpoint key).
     * @param reachable media-plane attribute: can we currently receive their audio.
     *        A false value does NOT mean "no speaker" — the speaker is present but
     *        their media path is down. Extend with more presentation attributes
     *        (muted, battery, encrypted, ...) here rather than splitting the type.
     */
    data class Speaking(
        val moduleId: String,
        val reachable: Boolean
    ) : FloorPresentation

    /**
     * Local holds protocol floor but uplink grant is not yet ready (ADR-0003 / ADR-0004).
     *
     * @param degraded health/confidence signals (e.g. ICE not ready); observe-only in V2 (R17).
     */
    data class Acquiring(val degraded: Boolean = false) : FloorPresentation

    /**
     * Local-only transient state.
     *
     * Never represents a remote floor request. An observer cannot see another
     * device "requesting" the floor; only the authority observes requests. Do not
     * render this as "M02 is requesting the floor".
     */
    object Requesting : FloorPresentation
}

/** True when someone holds the floor (control plane). */
val FloorPresentation.isSpeaking: Boolean
    get() = this is FloorPresentation.Speaking

/** Module identity of the current floor holder, or null when idle/requesting. */
val FloorPresentation.speakerModuleId: String?
    get() = (this as? FloorPresentation.Speaking)?.moduleId
