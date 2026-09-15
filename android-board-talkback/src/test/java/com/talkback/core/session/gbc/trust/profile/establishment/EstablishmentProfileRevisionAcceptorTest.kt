package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.conference.session.profile01.wire.Profile01ProfileBackedRecipientEstablishmentKeyLookup
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiFixtureSupport
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-EP-1 exit tests: EP1-1..EP1-6 establishment profile codec + acceptor.
 */
class EstablishmentProfileRevisionAcceptorTest {
    @Test
    fun ep1_1_validAuthenticatedRevision_peerBindingAccepted() {
        val harness = EstablishmentProfileRevisionFixtureSupport.harness()
        val payload = EstablishmentProfileRevisionFixtureSupport.duoPayload()

        val result = EstablishmentProfileRevisionFixtureSupport.accept(harness, payload)

        assertTrue(result is EstablishmentProfileRevisionAcceptResult.Accepted)
        val snapshot = harness.store.currentSnapshot()
        assertNotNull(snapshot)
        val lookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(harness.store)
        val found =
            lookup.lookupEstablishmentKey(
                EstablishmentProfileRevisionFixtureSupport.PEER_MODULE_ID,
                EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            )
        assertTrue(found is Profile01EstablishmentKeyLookupResult.Found)
    }

    @Test
    fun ep1_2_invalidSpkiOrUnsupportedAlgorithm_reject() {
        val harness = EstablishmentProfileRevisionFixtureSupport.harness()
        val invalidSpki =
            EstablishmentProfileRevisionFixtureSupport.duoPayload().copy(
                establishmentBindings =
                    listOf(
                        EstablishmentProfileRevisionFixtureSupport.peerBinding(
                            establishmentPublicKeySpki = byteArrayOf(0x01, 0x02),
                        ),
                    ),
            )

        val spkiResult = EstablishmentProfileRevisionFixtureSupport.accept(harness, invalidSpki)
        assertTrue(spkiResult is EstablishmentProfileRevisionAcceptResult.SemanticRejected)
        assertNull(harness.store.currentSnapshot())

        val encoded =
            EstablishmentProfileRevisionCanonicalCodec.encode(
                EstablishmentProfileRevisionFixtureSupport.duoPayload(),
            )
        val unsupportedAlgorithmBytes = encoded.copyOf()
        if (unsupportedAlgorithmBytes.size > 20) {
            unsupportedAlgorithmBytes[20] = 99.toByte()
        }
        assertNull(
            AuthenticatedEstablishmentProfileRevision.fromPapAuthenticatedSemantics(
                unsupportedAlgorithmBytes,
            ),
        )
        assertNull(harness.store.currentSnapshot())
    }

    @Test
    fun ep1_3_establishmentVersionRegressionOrReuse_reject() {
        val harness = EstablishmentProfileRevisionFixtureSupport.harness()
        EstablishmentProfileRevisionFixtureSupport.accept(
            harness,
            EstablishmentProfileRevisionFixtureSupport.duoPayload(revision = 10L, peerVersion = 4L),
        )

        val versionReuseDifferentSpki =
            EstablishmentProfileRevisionFixtureSupport.duoPayload(
                revision = 11L,
                peerVersion = 4L,
                peerSpki = EstablishmentProfileRevisionFixtureSupport.generateRsa3072Spki(),
            )
        val reuseResult = EstablishmentProfileRevisionFixtureSupport.accept(harness, versionReuseDifferentSpki)
        assertTrue(reuseResult is EstablishmentProfileRevisionAcceptResult.SemanticRejected)
        assertEquals(10L, harness.store.currentSnapshot()?.taskProfileRevision)

        val regressionPayload =
            EstablishmentProfileRevisionFixtureSupport.duoPayload(revision = 12L, peerVersion = 3L)
        val regressionResult = EstablishmentProfileRevisionFixtureSupport.accept(harness, regressionPayload)
        assertTrue(regressionResult is EstablishmentProfileRevisionAcceptResult.SemanticRejected)
        assertEquals(10L, harness.store.currentSnapshot()?.taskProfileRevision)
    }

    @Test
    fun ep1_4_unauthenticatedOrMalformedSemantics_zeroTrustStoreMutation() {
        val harness = EstablishmentProfileRevisionFixtureSupport.harness()
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        val parsed = AuthenticatedEstablishmentProfileRevision.fromPapAuthenticatedSemantics(garbage)
        assertNull(parsed)
        assertNull(harness.store.currentSnapshot())

        val nonCanonicalPayload = EstablishmentProfileRevisionFixtureSupport.duoPayload()
        val encoded = EstablishmentProfileRevisionCanonicalCodec.encode(nonCanonicalPayload)
        val tampered = encoded.copyOf()
        tampered[0] = 0x7F.toByte()
        assertNull(AuthenticatedEstablishmentProfileRevision.fromPapAuthenticatedSemantics(tampered))
        assertNull(harness.store.currentSnapshot())

        val invalidSemantic =
            EstablishmentProfileRevisionFixtureSupport.duoPayload().copy(
                establishmentBindings =
                    listOf(
                        EstablishmentProfileRevisionFixtureSupport.peerBinding(
                            establishmentPublicKeySpki = byteArrayOf(0x0A),
                        ),
                    ),
            )
        val reject = EstablishmentProfileRevisionFixtureSupport.accept(harness, invalidSemantic)
        assertTrue(reject is EstablishmentProfileRevisionAcceptResult.SemanticRejected)
        assertNull(harness.store.currentSnapshot())
    }

    @Test
    fun ep1_5_signingPresentEstablishmentAbsent_remainsUnknownModuleNoFallback() {
        val signingHarness = GenerationFactPtiFixtureSupport.harness()
        val signingPayload = GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 10L)
        val signingResult = GenerationFactPtiFixtureSupport.accept(signingHarness, signingPayload)
        assertTrue(signingResult is ProfileRevisionAcceptResult.Accepted)

        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        val lookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(establishmentStore)
        val unknown =
            lookup.lookupEstablishmentKey(
                EstablishmentProfileRevisionFixtureSupport.PEER_MODULE_ID,
                EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            )
        assertEquals(Profile01EstablishmentKeyLookupResult.UnknownModule, unknown)

        val localOnlyHarness = EstablishmentProfileRevisionFixtureSupport.harness()
        val localOnly =
            EstablishmentProfileRevisionFixtureSupport.duoPayload().copy(
                establishmentBindings =
                    listOf(
                        EstablishmentProfileRevisionFixtureSupport.localBinding(),
                    ),
            )
        EstablishmentProfileRevisionFixtureSupport.accept(localOnlyHarness, localOnly)
        val peerLookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(localOnlyHarness.store)
        val stillUnknown =
            peerLookup.lookupEstablishmentKey(
                EstablishmentProfileRevisionFixtureSupport.PEER_MODULE_ID,
                EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            )
        assertEquals(Profile01EstablishmentKeyLookupResult.UnknownModule, stillUnknown)
        assertNotNull(signingHarness.store.currentSnapshot())
    }

    @Test
    fun ep1_6_revisionUpdateAdvancesEstablishmentBinding() {
        val harness = EstablishmentProfileRevisionFixtureSupport.harness()
        EstablishmentProfileRevisionFixtureSupport.accept(
            harness,
            EstablishmentProfileRevisionFixtureSupport.duoPayload(revision = 10L, peerVersion = 4L),
        )

        val advanced =
            EstablishmentProfileRevisionFixtureSupport.duoPayload(
                revision = 11L,
                peerVersion = 5L,
            )
        val result = EstablishmentProfileRevisionFixtureSupport.accept(harness, advanced)
        assertTrue(result is EstablishmentProfileRevisionAcceptResult.Accepted)

        val snapshot = harness.store.currentSnapshot()
        assertNotNull(snapshot)
        assertEquals(11L, snapshot?.taskProfileRevision)
        val lookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(harness.store)
        val active = lookup.activeEstablishmentKey(EstablishmentProfileRevisionFixtureSupport.PEER_MODULE_ID)
        assertTrue(active is Profile01EstablishmentKeyLookupResult.Found)
        val found = active as Profile01EstablishmentKeyLookupResult.Found
        assertEquals(5L, found.ref.establishmentKeyVersion)
        assertEquals(
            GenerationFactKeyState.ACTIVE,
            snapshot?.bindingsByKey?.values?.first { it.moduleId == EstablishmentProfileRevisionFixtureSupport.PEER_MODULE_ID }?.keyState,
        )
    }
}
