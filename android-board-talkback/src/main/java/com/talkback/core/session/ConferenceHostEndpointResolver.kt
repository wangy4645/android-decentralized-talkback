package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId

/**
 * Resolves the conference host [EndpointAddress] for ICE-restart / rejoin recovery.
 * Reads through conference roster (not [TalkbackSession.groupMembers]).
 */
object ConferenceHostEndpointResolver {

    fun resolve(
        roster: List<EndpointAddress>,
        remote: EndpointAddress?,
        hostModuleId: String,
        fallbackEndpointId: EndpointId,
    ): EndpointAddress {
        roster.find { it.moduleId.value == hostModuleId }?.let { return it }
        remote?.takeIf { it.moduleId.value == hostModuleId }?.let { return it }
        return EndpointAddress(ModuleId(hostModuleId), fallbackEndpointId)
    }
}
