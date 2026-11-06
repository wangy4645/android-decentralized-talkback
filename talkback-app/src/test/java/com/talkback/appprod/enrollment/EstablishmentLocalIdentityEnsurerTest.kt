package com.talkback.appprod.enrollment

import com.talkback.core.session.gbc.trust.profile.establishment.TestLocalEstablishmentKeystoreIdentityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstablishmentLocalIdentityEnsurerTest {
    @Test
    fun ensureLocalIdentity_createsThenReusesWithoutSecondCreation() {
        val store = TestLocalEstablishmentKeystoreIdentityStore()
        val ensurer = EstablishmentLocalIdentityEnsurer(store)
        val first = ensurer.ensureLocalIdentity("M02")
        val second = ensurer.ensureLocalIdentity("M02")
        assertTrue(first is EstablishmentLocalIdentityEnsurer.Outcome.Ready)
        assertTrue(second is EstablishmentLocalIdentityEnsurer.Outcome.Ready)
        first as EstablishmentLocalIdentityEnsurer.Outcome.Ready
        second as EstablishmentLocalIdentityEnsurer.Outcome.Ready
        assertTrue(first.newlyCreated)
        assertEquals(false, second.newlyCreated)
        assertEquals(first.identity.publicKeySpki.toList(), second.identity.publicKeySpki.toList())
    }

    @Test
    fun ensureLocalIdentity_normalizesModuleId() {
        val store = TestLocalEstablishmentKeystoreIdentityStore()
        val ensurer = EstablishmentLocalIdentityEnsurer(store)
        val outcome = ensurer.ensureLocalIdentity(" m03 ")
        assertTrue(outcome is EstablishmentLocalIdentityEnsurer.Outcome.Ready)
        outcome as EstablishmentLocalIdentityEnsurer.Outcome.Ready
        assertEquals("M03", outcome.identity.moduleId)
    }
}
