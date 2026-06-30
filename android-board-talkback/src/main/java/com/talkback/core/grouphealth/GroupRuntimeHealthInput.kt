package com.talkback.core.grouphealth

import com.talkback.core.model.ModuleId
import com.talkback.core.session.TalkbackSession

/**
 * Facts bundle for [GroupRuntimeHealthProjector] (ADR-0008). Coordinator assembles; projector stays pure.
 */
data class GroupRuntimeHealthInput(
    val localModuleId: ModuleId,
    val session: TalkbackSession?,
    val dialablePeerCount: Int = 0,
    val membershipDigestAlignedWithAuthority: Boolean = false,
    val peerMediaConnected: Set<String> = emptySet(),
    val iceStateForModule: (String) -> String? = { null },
    val channelGated: Boolean = false,
    val convergenceAgeMs: Long = 0L
)
