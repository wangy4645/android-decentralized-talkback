package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.OperationalProfileTestSigning
import com.talkback.core.session.gbc.trust.profile.ProfileAuthenticationResult
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptResult
import com.talkback.core.session.gbc.trust.profile.establishment.FileOperationalTrustAnchorPersistence.Companion.encodeEstablished
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0057 Operational Establishment conformance (OE-EG1..OE-EG6).
 */
class Adr0057OperationalEstablishmentFixturesTest {
    @Test
    fun oe_eg1_establishmentRequiresPapT2Authorization() {
        val file = newAnchorFile()
        val harness = OperationalEstablishmentFixtureSupport.productHarness(file)
        assertTrue(
            OperationalEstablishmentFixtureSupport.establishProductAnchor(harness)
                is EstablishmentResult.Established,
        )
        assertTrue(
            runCatching {
                EstablishmentRecord(
                    deploymentTrustDomainId = OperationalEstablishmentFixtureSupport.TRUST_DOMAIN,
                    operationalAuthorityPublicKeySpki = byteArrayOf(1),
                    establishmentRecordIdentity = "",
                    provenanceClass = EstablishmentProvenanceClass.OPERATOR_OUT_OF_BAND,
                )
            }.isFailure,
        )
    }

    @Test
    fun oe_eg2_createOnceConcurrent_onlyOneCommit() {
        val file = newAnchorFile()
        val persistence = FileOperationalTrustAnchorPersistence(file)
        val establisher = OperationalAnchorEstablisher(persistence)
        val record = OperationalEstablishmentFixtureSupport.establishmentRecord()
        val auth = PapT2EstablishmentAuthorizationIssuer().authorize(record)!!
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val established = AtomicInteger(0)
        val already = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        try {
            repeat(8) {
                pool.submit {
                    start.await(3, TimeUnit.SECONDS)
                    when (val r = establisher.establish(auth)) {
                        is EstablishmentResult.Established -> established.incrementAndGet()
                        is EstablishmentResult.AlreadyEstablished -> already.incrementAndGet()
                        is EstablishmentResult.Rejected -> rejected.incrementAndGet()
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, established.get())
            assertEquals(7, already.get())
            assertEquals(0, rejected.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun oe_eg2_differentAnchorAfterEstablished_rejected() {
        val file = newAnchorFile()
        val harness = OperationalEstablishmentFixtureSupport.productHarness(file)
        assertTrue(OperationalEstablishmentFixtureSupport.establishProductAnchor(harness) is EstablishmentResult.Established)
        val otherRecord =
            OperationalEstablishmentFixtureSupport.establishmentRecord().copy(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.OTHER_TRUST_DOMAIN,
            )
        val otherAuth = PapT2EstablishmentAuthorizationIssuer().authorize(otherRecord)!!
        val retry = harness.establisher.establish(otherAuth)
        assertTrue(retry is EstablishmentResult.Rejected)
    }

    @Test
    fun oe_eg3_restartRecovery_sameIdentity() {
        val file = newAnchorFile()
        val harness = OperationalEstablishmentFixtureSupport.productHarness(file)
        assertTrue(OperationalEstablishmentFixtureSupport.establishProductAnchor(harness) is EstablishmentResult.Established)
        val persistence2 = FileOperationalTrustAnchorPersistence(file)
        val state = persistence2.currentState()
        assertTrue(state is AnchorPersistenceState.Established)
        val established = state as AnchorPersistenceState.Established
        assertEquals(OperationalEstablishmentFixtureSupport.TRUST_DOMAIN, established.anchor.deploymentTrustDomainId)
        assertEquals(OperationalEstablishmentFixtureSupport.RECORD_ID, established.establishmentRecordIdentity)
    }

    @Test
    fun oe_eg3_corruptPersistedState_failClosed() {
        val file = newAnchorFile()
        val record = OperationalEstablishmentFixtureSupport.establishmentRecord()
        Files.write(
            file,
            encodeEstablished(record.toAnchor(), record.establishmentRecordIdentity).copyOf().also {
                it[10] = (it[10].toInt() xor 0xFF).toByte()
            },
        )
        val persistence = FileOperationalTrustAnchorPersistence(file)
        assertTrue(persistence.currentState() is AnchorPersistenceState.Empty)
        val source = OperationalTrustAnchorBootstrap.loadSource(persistence)
        assertNull(source.pinnedAnchor(OperationalEstablishmentFixtureSupport.TRUST_DOMAIN))
    }

    @Test
    fun oe_eg4_domainBinding_mismatchNotExposed() {
        val file = newAnchorFile()
        val harness = OperationalEstablishmentFixtureSupport.productHarness(file)
        assertTrue(OperationalEstablishmentFixtureSupport.establishProductAnchor(harness) is EstablishmentResult.Established)
        assertNull(
            harness.anchorSource.pinnedAnchor(GenerationFactPtiLayerBFixtureSupport.OTHER_TRUST_DOMAIN),
        )
        assertNotNull(harness.anchorSource.pinnedAnchor(OperationalEstablishmentFixtureSupport.TRUST_DOMAIN))
    }

    @Test
    fun oe_eg5_provisioningCannotOverwriteAnchor() {
        val file = newAnchorFile()
        val harness = OperationalEstablishmentFixtureSupport.productHarness(file)
        assertTrue(OperationalEstablishmentFixtureSupport.establishProductAnchor(harness) is EstablishmentResult.Established)
        val before = (harness.persistence.currentState() as AnchorPersistenceState.Established).anchor
        val overwriteAuth =
            PapT2EstablishmentAuthorizationIssuer().authorize(
                OperationalEstablishmentFixtureSupport.establishmentRecord().copy(
                    operationalAuthorityPublicKeySpki = ByteArray(32) { 7 },
                ),
            )!!
        val result = harness.establisher.establish(overwriteAuth)
        assertTrue(result is EstablishmentResult.Rejected)
        val after = (harness.persistence.currentState() as AnchorPersistenceState.Established).anchor
        assertTrue(before.operationalAuthorityPublicKeySpki.contentEquals(after.operationalAuthorityPublicKeySpki))
    }

    @Test
    fun oe_eg6_productLoadPath_authenticatorSucceedsAfterRestart() {
        val file = newAnchorFile()
        val harness = OperationalEstablishmentFixtureSupport.productHarness(file)
        assertTrue(OperationalEstablishmentFixtureSupport.establishProductAnchor(harness) is EstablishmentResult.Established)

        val persistence2 = FileOperationalTrustAnchorPersistence(file)
        val source2 = OperationalTrustAnchorBootstrap.loadSource(persistence2)
        val authenticator2 =
            com.talkback.core.session.gbc.trust.profile.ProductionProfileRevisionAuthenticator(
                source2,
                OperationalEstablishmentFixtureSupport.TRUST_DOMAIN,
            )
        val acceptor2 =
            com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptor(
                authenticator2,
                com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore(),
            )
        val payload = GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2(revision = 5)
        val signed = OperationalProfileTestSigning.signPayloadV2(payload)
        val auth = authenticator2.authenticate(signed)
        assertTrue(auth is ProfileAuthenticationResult.Authenticated)
        val accepted = acceptor2.acceptProtectedRevision(signed)
        assertTrue(accepted is ProfileRevisionAcceptResult.Accepted)
    }

    @Test
    fun oe_eg6_emptyState_failClosed() {
        val file = newAnchorFile()
        val source = OperationalTrustAnchorBootstrap.loadSource(FileOperationalTrustAnchorPersistence(file))
        val authenticator =
            com.talkback.core.session.gbc.trust.profile.ProductionProfileRevisionAuthenticator(
                source,
                OperationalEstablishmentFixtureSupport.TRUST_DOMAIN,
            )
        val payload = GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2()
        val signed = OperationalProfileTestSigning.signPayloadV2(payload)
        val result = authenticator.authenticate(signed)
        assertTrue(result is ProfileAuthenticationResult.Rejected)
    }

    private fun newAnchorFile(): java.nio.file.Path =
        Files.createTempDirectory("oe-anchor").resolve("operational-trust-anchor.bin")
}
