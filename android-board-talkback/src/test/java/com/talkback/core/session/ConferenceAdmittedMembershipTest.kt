package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceAdmittedMembershipTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val m04 = ModuleId("M04")
    private val m05 = ModuleId("M05")
    private val local = EndpointAddress(m01, EndpointId("E01"))
    private val e02 = EndpointAddress(m02, EndpointId("E01"))
    private val e03 = EndpointAddress(m03, EndpointId("E01"))
    private val e04 = EndpointAddress(m04, EndpointId("E01"))
    private val e05 = EndpointAddress(m05, EndpointId("E01"))

    @Test
    fun acceptedMember_isAdmitted() {
        assertTrue(
            ConferenceAdmittedMembership.isAdmittedRemote(
                invite = InviteState.ACCEPTED,
                media = MediaState.CONNECTED,
                canonicalMember = true,
                leftMember = false,
            )
        )
    }

    @Test
    fun firstRingInvite_isNotAdmitted() {
        assertFalse(
            ConferenceAdmittedMembership.isAdmittedRemote(
                invite = InviteState.INVITING,
                media = MediaState.NONE,
                canonicalMember = false,
                leftMember = false,
            )
        )
        assertFalse(
            ConferenceAdmittedMembership.isAdmittedRemote(
                invite = InviteState.RINGING,
                media = MediaState.NONE,
                canonicalMember = false,
                leftMember = false,
            )
        )
    }

    @Test
    fun reconnectInviting_staysAdmitted() {
        assertTrue(
            ConferenceAdmittedMembership.isAdmittedRemote(
                invite = InviteState.INVITING,
                media = MediaState.RECONNECTING,
                canonicalMember = true,
                leftMember = false,
            )
        )
        assertTrue(
            ConferenceAdmittedMembership.isAdmittedRemote(
                invite = InviteState.INVITING,
                media = MediaState.RECONNECTING,
                canonicalMember = false,
                leftMember = false,
            )
        )
    }

    @Test
    fun canonicalMember_staysAdmittedEvenIfInviteFlipped() {
        assertTrue(
            ConferenceAdmittedMembership.isAdmittedRemote(
                invite = InviteState.INVITING,
                media = MediaState.CONNECTING,
                canonicalMember = true,
                leftMember = false,
            )
        )
        assertTrue(
            ConferenceAdmittedMembership.isAdmittedRemote(
                invite = InviteState.NONE,
                media = MediaState.RECONNECTING,
                canonicalMember = true,
                leftMember = false,
            )
        )
    }

    @Test
    fun leftMember_isNotAdmitted() {
        assertFalse(
            ConferenceAdmittedMembership.isAdmittedRemote(
                invite = InviteState.ACCEPTED,
                media = MediaState.CONNECTED,
                canonicalMember = false,
                leftMember = true,
            )
        )
    }

    @Test
    fun m05Rejoin_doesNotDropReconnectPeersFromAdmittedSet() {
        val admitted = ConferenceAdmittedMembership.admittedEndpoints(
            local,
            listOf(
                fact(e02, InviteState.ACCEPTED, MediaState.CONNECTED, canonical = true),
                fact(e03, InviteState.INVITING, MediaState.RECONNECTING, canonical = true),
                fact(e04, InviteState.INVITING, MediaState.RECONNECTING, canonical = true),
                fact(e05, InviteState.ACCEPTED, MediaState.CONNECTING, canonical = true),
            ),
        )
        assertEquals(listOf("M01", "M02", "M03", "M04", "M05"), admitted.map { it.moduleId.value })
    }

    @Test
    fun firstAccept_doesNotAdmitUnansweredInvitees() {
        val admitted = ConferenceAdmittedMembership.admittedEndpoints(
            local,
            listOf(
                fact(e02, InviteState.ACCEPTED, MediaState.CONNECTING, canonical = true),
                fact(e03, InviteState.INVITING, MediaState.NONE, canonical = false),
                fact(e04, InviteState.RINGING, MediaState.NONE, canonical = false),
            ),
        )
        assertEquals(listOf("M01", "M02"), admitted.map { it.moduleId.value })
    }

    @Test
    fun snapshotRecipients_includeCanonicalEvenIfMissingFromAdmitted() {
        val recipients = ConferenceAdmittedMembership.snapshotRecipients(
            local = local,
            canonicalRemotes = listOf(e02, e03, e04),
            admittedRemotes = listOf(local, e02, e05),
        )
        assertEquals(listOf("M02", "M03", "M04", "M05"), recipients.map { it.moduleId.value })
    }

    private fun fact(
        endpoint: EndpointAddress,
        invite: InviteState,
        media: MediaState,
        canonical: Boolean,
        left: Boolean = false,
    ) = ConferenceAdmittedMembership.RemoteAdmissionFact(
        endpoint = endpoint,
        invite = invite,
        media = media,
        canonicalMember = canonical,
        leftMember = left,
    )
}
