package com.talkback.core.session.gbc.trust.profile.establishment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EstablishmentProvisioningScopeTest {
    @Test
    fun quadMeeting_hostRequiresRemoteLookups_only() {
        val meeting = setOf("M01", "M02", "M03", "M04")
        val hostLookups =
            EstablishmentProvisioningScope.requiredHostRecipientLookups(
                meetingModuleIds = meeting,
                hostModuleId = "M01",
            )
        assertEquals(setOf("M02", "M03", "M04"), hostLookups)
        assertFalse(hostLookups.contains("M01"))
    }

    @Test
    fun bindingCatalog_coversFullMeetingSet() {
        val meeting = setOf("M01", "M02", "M03", "M04")
        assertEquals(meeting, EstablishmentProvisioningScope.requiredBindingCatalogModules(meeting))
    }
}
