package com.talkback.core.session

import com.talkback.core.model.EndpointAddress

/**
 * Conference admitted-set rules for Host snapshot / UI roster.
 *
 * Excludes first-ring invitees. Keeps in-meeting members whose invite was
 * temporarily flipped to [InviteState.INVITING] by a reconnect offer.
 */
object ConferenceAdmittedMembership {

    data class RemoteAdmissionFact(
        val endpoint: EndpointAddress,
        val invite: InviteState,
        val media: MediaState,
        val canonicalMember: Boolean,
        val leftMember: Boolean,
    )

    fun isAdmittedRemote(
        invite: InviteState,
        media: MediaState,
        canonicalMember: Boolean,
        leftMember: Boolean,
    ): Boolean {
        if (leftMember) return false
        if (invite == InviteState.DECLINED || invite == InviteState.EXPIRED) return false
        if (canonicalMember) return true
        if (invite == InviteState.ACCEPTED) return true
        return invite == InviteState.INVITING && media == MediaState.RECONNECTING
    }

    fun admittedEndpoints(
        local: EndpointAddress,
        remotes: List<RemoteAdmissionFact>,
    ): List<EndpointAddress> {
        val byModule = LinkedHashMap<String, EndpointAddress>()
        byModule[local.moduleId.value] = local
        remotes.forEach { fact ->
            if (isAdmittedRemote(fact.invite, fact.media, fact.canonicalMember, fact.leftMember)) {
                byModule.putIfAbsent(fact.endpoint.moduleId.value, fact.endpoint)
            }
        }
        return byModule.values.toList()
    }

    fun snapshotRecipients(
        local: EndpointAddress,
        canonicalRemotes: List<EndpointAddress>,
        admittedRemotes: List<EndpointAddress>,
    ): List<EndpointAddress> {
        val byModule = LinkedHashMap<String, EndpointAddress>()
        canonicalRemotes.forEach { endpoint ->
            if (endpoint.moduleId != local.moduleId) {
                byModule[endpoint.moduleId.value] = endpoint
            }
        }
        admittedRemotes.forEach { endpoint ->
            if (endpoint.moduleId != local.moduleId) {
                byModule.putIfAbsent(endpoint.moduleId.value, endpoint)
            }
        }
        return byModule.values.toList()
    }
}
