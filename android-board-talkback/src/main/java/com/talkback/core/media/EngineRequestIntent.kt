package com.talkback.core.media

/**
 * ADR-0061: obligation identity for deferred engine provision.
 *
 * [CREATE_CONTINUATION] is authorized only from
 * `TalkbackCoordinator.beginConferenceInviteWhenEngineReady` → `requestConferenceEngine`.
 * Do not use on other CONFERENCE enqueue sites (rejoin, recovery, IGIE/GIE).
 */
enum class EngineRequestIntent {
    /** Default / hangup stacking — ADR-0057 + m03 semantics unchanged. */
    DEFAULT,

    /** Conference CREATE invite — eligible for post-[mediaSessionClose] continuation drain. */
    CREATE_CONTINUATION,
}
