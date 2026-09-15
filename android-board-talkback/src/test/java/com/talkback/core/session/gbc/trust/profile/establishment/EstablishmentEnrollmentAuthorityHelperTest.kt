package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.OperationalProfileTestSigning
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR-EP-3 exit tests: E3-1..E3-7 enrollment evidence + authority helper + PAP chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EstablishmentEnrollmentAuthorityHelperTest {
    private val keystoreStore = TestLocalEstablishmentKeystoreIdentityStore()
    private val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
    private val anchorStore = GenerationFactPtiLayerBFixtureSupport.anchorStore()
    private val authenticator =
        EstablishmentProfileOperationalAuthenticator(
            anchorStore,
            GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
        )
    private val publicationSeam =
        EstablishmentProfilePublicationSeam { protected ->
            OperationalProfileTestSigning.signProtectedRevision(protected)
        }

    @Before
    fun setUp() {
        keystoreStore.clear()
        establishmentStore.clear()
    }

    @After
    fun tearDown() {
        keystoreStore.clear()
        establishmentStore.clear()
    }

    @Test
    fun e3_1_localKeystoreIdentity_producesDeterministicCanonicalEvidence() {
        val identity = provisionIdentity(VERSION_ONE)
        val first = produceEvidence(identity, generation = 1L)
        val second = produceEvidence(identity, generation = 1L)
        assertArrayEquals(first.canonicalBytes, second.canonicalBytes)
        assertEquals(identity.publicKeySpki.sha256Hex(), first.evidence.publicKeyFingerprintSha256Hex)
    }

    @Test
    fun e3_2_evidenceContainsOnlyPublicMaterial() {
        val identity = provisionIdentity(VERSION_ONE)
        val produced = produceEvidence(identity, generation = 1L)
        val encoded = EstablishmentEnrollmentEvidenceCanonicalCodec.encode(produced.evidence)
        val decoded = EstablishmentEnrollmentEvidenceCanonicalCodec.decode(encoded)!!
        assertTrue(decoded.publicKeySpki.isNotEmpty())
        assertArrayEquals(identity.publicKeySpki, decoded.publicKeySpki)
        assertEquals(identity.keyAlias, decoded.keyAlias)
        assertEquals(identity.moduleId, decoded.moduleId)
    }

    @Test
    fun e3_3_authorityAssignsMonotonicNewEstablishmentKeyVersion() {
        val identity = provisionIdentity(VERSION_ONE)
        val evidence = produceEvidence(identity, generation = 1L).evidence
        val first =
            approve(
                evidence = evidence,
                revision = 10L,
                trigger = EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT,
            )
        assertTrue(first is EstablishmentEnrollmentAuthorityResult.Approved)
        assertEquals(1L, (first as EstablishmentEnrollmentAuthorityResult.Approved).draft.assignedEstablishmentKeyVersion)

        acceptDraft((first as EstablishmentEnrollmentAuthorityResult.Approved).draft)

        val rotatedIdentity = provisionIdentity(VERSION_TWO)
        val rotatedEvidence = produceEvidence(rotatedIdentity, generation = 2L).evidence
        val second =
            approve(
                evidence = rotatedEvidence,
                revision = 11L,
                trigger = EstablishmentEnrollmentTrigger.OPERATOR_KEY_ROTATION,
            )
        assertTrue(second is EstablishmentEnrollmentAuthorityResult.Approved)
        assertEquals(2L, (second as EstablishmentEnrollmentAuthorityResult.Approved).draft.assignedEstablishmentKeyVersion)
    }

    @Test
    fun e3_4_sameAuthorizedKey_idempotentEnrollmentDoesNotConsumeNewVersion() {
        val identity = provisionIdentity(VERSION_ONE)
        val evidence = produceEvidence(identity, generation = 1L).evidence
        val first = approve(evidence, revision = 10L, EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT)
        acceptDraft((first as EstablishmentEnrollmentAuthorityResult.Approved).draft)
        val second =
            approve(
                evidence = evidence,
                revision = 11L,
                trigger = EstablishmentEnrollmentTrigger.IDEMPOTENT_REENROLLMENT,
            )
        assertTrue(second is EstablishmentEnrollmentAuthorityResult.Approved)
        assertEquals(1L, (second as EstablishmentEnrollmentAuthorityResult.Approved).draft.assignedEstablishmentKeyVersion)
    }

    @Test
    fun e3_5_newSpkiRotation_assignsNewVersion_oldVersionNotReused() {
        val identityV1 = provisionIdentity(VERSION_ONE)
        val evidenceV1 = produceEvidence(identityV1, generation = 1L).evidence
        acceptDraft(
            (approve(evidenceV1, 10L, EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT) as
                EstablishmentEnrollmentAuthorityResult.Approved).draft,
        )

        val identityV2 = provisionIdentity(VERSION_TWO)
        val evidenceV2 = produceEvidence(identityV2, generation = 2L).evidence
        val rotated =
            approve(evidenceV2, 11L, EstablishmentEnrollmentTrigger.OPERATOR_KEY_ROTATION)
        assertTrue(rotated is EstablishmentEnrollmentAuthorityResult.Approved)
        assertEquals(2L, (rotated as EstablishmentEnrollmentAuthorityResult.Approved).draft.assignedEstablishmentKeyVersion)
        assertNotEquals(
            evidenceV1.publicKeySpki.toList(),
            evidenceV2.publicKeySpki.toList(),
        )
    }

    @Test
    fun e3_6_forgedOrUnauthorizedInput_noProfileMutation() {
        val identity = provisionIdentity(VERSION_ONE)
        val evidence = produceEvidence(identity, generation = 1L).evidence
        val forged =
            approve(
                evidence = evidence,
                revision = 10L,
                trigger = EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT,
                targetModuleId = "M99",
            )
        assertTrue(forged is EstablishmentEnrollmentAuthorityResult.Rejected)
        assertEquals(null, establishmentStore.currentSnapshot())

        val unauthorized =
            approve(
                evidence = evidence,
                revision = 10L,
                trigger = EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT,
                operatorAuthorized = false,
            )
        assertTrue(unauthorized is EstablishmentEnrollmentAuthorityResult.Rejected)
        assertEquals(null, establishmentStore.currentSnapshot())

        val malformed =
            approve(
                evidence = evidence.copy(publicKeyFingerprintSha256Hex = "00"),
                revision = 10L,
                trigger = EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT,
            )
        assertTrue(malformed is EstablishmentEnrollmentAuthorityResult.Rejected)
        assertEquals(null, establishmentStore.currentSnapshot())
    }

    @Test
    fun e3_7_papPath_acceptAndVerify_closesProvisioningAuthorityChain() {
        val identity = provisionIdentity(VERSION_ONE)
        val evidence = produceEvidence(identity, generation = 1L).evidence
        val approved =
            approve(evidence, revision = 10L, EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT)
        assertTrue(approved is EstablishmentEnrollmentAuthorityResult.Approved)
        val result =
            EstablishmentEnrollmentWorkflow.publishAcceptAndVerify(
                keystoreStore = keystoreStore,
                establishmentStore = establishmentStore,
                authenticator = authenticator,
                publicationSeam = publicationSeam,
                authorityDraft = (approved as EstablishmentEnrollmentAuthorityResult.Approved).draft,
            )
        assertTrue(result is EstablishmentEnrollmentWorkflow.EnrollmentWorkflowResult.Verified)
        val snapshot = establishmentStore.currentSnapshot()
        assertNotNull(snapshot)
        assertEquals(10L, snapshot?.taskProfileRevision)
        assertEquals(1, snapshot?.bindingsByKey?.size)
    }

    @Test
    fun runtimeMismatch_isNotRotationTrigger() {
        val identityV1 = provisionIdentity(VERSION_ONE)
        val evidenceV1 = produceEvidence(identityV1, generation = 1L).evidence
        acceptDraft(
            (approve(evidenceV1, 10L, EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT) as
                EstablishmentEnrollmentAuthorityResult.Approved).draft,
        )

        val evidenceV2 = produceEvidence(provisionIdentity(VERSION_TWO), generation = 2L).evidence
        val rejectedWithoutExplicitRotation =
            approve(evidenceV2, 11L, EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT)
        assertTrue(rejectedWithoutExplicitRotation is EstablishmentEnrollmentAuthorityResult.Rejected)

        val explicitRotation =
            approve(evidenceV2, 11L, EstablishmentEnrollmentTrigger.OPERATOR_KEY_ROTATION)
        assertTrue(explicitRotation is EstablishmentEnrollmentAuthorityResult.Approved)
        assertEquals(
            2L,
            (explicitRotation as EstablishmentEnrollmentAuthorityResult.Approved).draft.assignedEstablishmentKeyVersion,
        )
    }

    private fun provisionIdentity(version: Long): Profile01LocalEstablishmentIdentitySnapshot {
        val result =
            keystoreStore.provisionFirstIdentity(MODULE_ID, version) as
                LocalEstablishmentKeystoreProvisionResult.Created
        return result.identity
    }

    private fun produceEvidence(
        identity: Profile01LocalEstablishmentIdentitySnapshot,
        generation: Long,
    ): EstablishmentEnrollmentEvidenceProduceResult.Ready {
        val produced = EstablishmentEnrollmentEvidenceProducer.produce(identity, generation)
        assertTrue(produced is EstablishmentEnrollmentEvidenceProduceResult.Ready)
        return produced as EstablishmentEnrollmentEvidenceProduceResult.Ready
    }

    private fun approve(
        evidence: EstablishmentEnrollmentEvidence,
        revision: Long,
        trigger: EstablishmentEnrollmentTrigger,
        targetModuleId: String = MODULE_ID,
        operatorAuthorized: Boolean = true,
    ): EstablishmentEnrollmentAuthorityResult =
        EstablishmentEnrollmentAuthorityHelper.approveEnrollment(
            request =
                EstablishmentEnrollmentAuthorityRequest(
                    operatorAuthorized = operatorAuthorized,
                    targetModuleId = targetModuleId,
                    evidence = evidence,
                    trigger = trigger,
                ),
            currentState = establishmentStore.currentSnapshot(),
            nextTaskProfileRevision = revision,
            deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
            localModuleId = MODULE_ID,
        )

    private fun acceptDraft(draft: EstablishmentEnrollmentRevisionDraft) {
        val result =
            EstablishmentEnrollmentWorkflow.publishAcceptAndVerify(
                keystoreStore = keystoreStore,
                establishmentStore = establishmentStore,
                authenticator = authenticator,
                publicationSeam = publicationSeam,
                authorityDraft = draft,
            )
        assertTrue(result is EstablishmentEnrollmentWorkflow.EnrollmentWorkflowResult.Verified)
    }

    companion object {
        private const val MODULE_ID = "M01"
        private const val VERSION_ONE = 1L
        private const val VERSION_TWO = 2L
    }
}
