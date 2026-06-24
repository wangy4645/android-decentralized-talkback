package com.talkback.core.session

/**
 * Explicit channel lifecycle inputs. Side effects (mesh recovery, reconcile) attach to these
 * events — not to UI polling.
 */
enum class ChannelLifecycleEvent {
    /** Last CONFERENCE session on the channel ended (local or remote hangup). */
    ConferenceEnded,
    /** User left Meeting tab / meeting-preferred cleared on this channel. */
    MeetingTabReleased,
    /** User pressed PTT or explicitly requested post-meeting GROUP recovery. */
    PttRecoveryRequested,
    /** Dialable peer roster grew; cold-start bootstrap may proceed. */
    PeersDiscovered
}
