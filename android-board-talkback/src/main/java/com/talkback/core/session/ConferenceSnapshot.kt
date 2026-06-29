package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.ModuleId

data class ConferenceSnapshot(
    val roster: List<EndpointAddress>,
    val memberViews: List<MemberView>,
    val everConnectedModules: Set<ModuleId>
)
