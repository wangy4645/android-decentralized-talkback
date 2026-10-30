package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingOriginAuthorityTest {
    @Test
    fun hostOwnerKeepsOriginWhenAnchorIsDifferent() {
        assertTrue(
            MeetingOriginAuthority.isLocalOriginOwner(
                sessionTypeConference = true,
                sessionAccepted = true,
                localModuleId = "M05",
                canonicalHostModuleId = "M05",
                boundInitiatorModuleId = "M05",
            ),
        )
    }

    @Test
    fun anchorParticipantCannotPublishOrigin() {
        assertFalse(
            MeetingOriginAuthority.isLocalOriginOwner(
                sessionTypeConference = true,
                sessionAccepted = true,
                localModuleId = "M01",
                canonicalHostModuleId = "M05",
                boundInitiatorModuleId = "M05",
            ),
        )
    }

    @Test
    fun missingCanonicalHostDoesNotGrantOrigin() {
        assertFalse(
            MeetingOriginAuthority.isLocalOriginOwner(
                sessionTypeConference = true,
                sessionAccepted = true,
                localModuleId = "M05",
                canonicalHostModuleId = null,
                boundInitiatorModuleId = "M05",
            ),
        )
    }

    @Test
    fun initiatorMismatchWithCanonicalHostIsRejected() {
        assertFalse(
            MeetingOriginAuthority.isLocalOriginOwner(
                sessionTypeConference = true,
                sessionAccepted = true,
                localModuleId = "M05",
                canonicalHostModuleId = "M05",
                boundInitiatorModuleId = "M01",
            ),
        )
    }

    @Test
    fun coincidentHostAndAnchorStillOwnsOrigin() {
        assertTrue(
            MeetingOriginAuthority.isLocalOriginOwner(
                sessionTypeConference = true,
                sessionAccepted = true,
                localModuleId = "M01",
                canonicalHostModuleId = "M01",
                boundInitiatorModuleId = "M01",
            ),
        )
    }
}
