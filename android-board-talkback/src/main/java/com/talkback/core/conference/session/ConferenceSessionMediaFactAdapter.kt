package com.talkback.core.conference.session

/**
 * Pure control-plane → wiring fact mapping.
 *
 * Does not install/revoke/replace/stop — only translates authoritative inputs.
 */
object ConferenceSessionMediaFactAdapter {
    fun toSessionFact(control: ConferenceMediaSessionControlFact): ConferenceSessionMediaFact =
        ConferenceSessionMediaFact(
            sessionId = control.conferenceId,
            generation = control.conferenceEpoch,
            mediaKeyEpoch = control.mediaKeyEpoch,
            endpoint = control.endpoint,
            networkInterfaceName = control.networkInterfaceName,
            masterKey = control.masterKey.copyOf(),
            masterSalt = control.masterSalt.copyOf(),
            keyContextHint64 = control.keyContextHint64.copyOf(),
        )

    fun toMemberBinding(control: ConferenceMediaMemberControlFact): MemberBindingFact =
        MemberBindingFact(
            moduleId = control.moduleId,
            incarnationId = control.membershipIncarnationId,
            ssrc = control.ssrc,
            sourceAdmissionKey48 = control.sourceAdmissionKey48.copyOf(),
            mediaKeyEpoch = control.mediaKeyEpoch,
        )

    fun rosterEpochMatches(
        control: ConferenceMediaSessionControlFact,
        rosterEpoch: Long,
    ): Boolean = control.membershipVersion == rosterEpoch

    fun memberMatchesSession(
        session: ConferenceMediaSessionControlFact,
        member: ConferenceMediaMemberControlFact,
    ): Boolean =
        member.conferenceId == session.conferenceId &&
            member.membershipVersion == session.membershipVersion &&
            member.mediaKeyEpoch == session.mediaKeyEpoch

    fun isPublishableSession(control: ConferenceMediaSessionControlFact): Boolean =
        !control.superseded

    fun isPublishableMember(
        session: ConferenceMediaSessionControlFact,
        member: ConferenceMediaMemberControlFact,
    ): Boolean = !member.superseded && memberMatchesSession(session, member)

    fun memberFactsEqual(
        left: ConferenceMediaMemberControlFact,
        right: ConferenceMediaMemberControlFact,
    ): Boolean =
        left.moduleId == right.moduleId &&
            left.membershipIncarnationId == right.membershipIncarnationId &&
            left.ssrc == right.ssrc &&
            left.mediaKeyEpoch == right.mediaKeyEpoch &&
            left.membershipVersion == right.membershipVersion &&
            left.sourceAdmissionKey48.contentEquals(right.sourceAdmissionKey48)
}
