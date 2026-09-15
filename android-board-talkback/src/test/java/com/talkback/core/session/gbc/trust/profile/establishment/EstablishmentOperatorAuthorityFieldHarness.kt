package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.OperationalProfileTestSigning
import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition
import java.io.File
import java.util.Base64

/**
 * Workstation-side operator authority harness (field helper).
 *
 * Reads pulled `M01-evidence.txt` / `M02-evidence.txt` (and optional `M04-evidence.txt`
 * for trio); writes signed deliveries + manifest.
 * Invoked from Gradle when `EP_BATCH_DIR` is set.
 */
object EstablishmentOperatorAuthorityFieldHarness {
    data class Evidence(
        val moduleId: String,
        val publicKeyFingerprintSha256Hex: String,
        val keyAlias: String,
        val localKeyGeneration: Long,
        val publicKeySpki: ByteArray,
        val operatorAssignedEstablishmentKeyVersion: Long,
    )

    data class AuthorityResult(
        val success: Boolean,
        val message: String,
        val outputs: Map<String, File>,
    )

    fun run(batchDir: File): AuthorityResult {
        require(batchDir.isDirectory) { "batch dir missing: ${batchDir.absolutePath}" }
        val taskProfileRevision =
            System.getenv("EP_TASK_PROFILE_REVISION")?.toLongOrNull() ?: 20L
        require(taskProfileRevision > 1L) { "taskProfileRevision must be > 1" }
        val domain =
            System.getenv("EP_DEPLOYMENT_TRUST_DOMAIN")
                ?: GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN

        val m01 = loadEvidence(File(batchDir, "M01-evidence.txt"))
        val m02 = loadEvidence(File(batchDir, "M02-evidence.txt"))
        val thirdPeerFile = resolveThirdPeerEvidenceFile(batchDir)
        return if (thirdPeerFile != null) {
            val thirdPeer = loadEvidence(thirdPeerFile)
            runTrio(batchDir, m01, m02, thirdPeer, taskProfileRevision, domain)
        } else {
            runDuo(batchDir, m01, m02, taskProfileRevision, domain)
        }
    }

    private fun runDuo(
        batchDir: File,
        m01: Evidence,
        m02: Evidence,
        taskProfileRevision: Long,
        domain: String,
    ): AuthorityResult {
        val stagingRevision = taskProfileRevision - 1L
        val m01Enrollment = toEnrollmentEvidence(m01)
        val m02Enrollment = toEnrollmentEvidence(m02)

        val stagedM01 =
            EstablishmentEnrollmentAuthorityHelper.approveEnrollment(
                request =
                    EstablishmentEnrollmentAuthorityRequest(
                        operatorAuthorized = true,
                        targetModuleId = m01.moduleId,
                        evidence = m01Enrollment,
                        trigger = EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT,
                    ),
                currentState = null,
                nextTaskProfileRevision = stagingRevision,
                deploymentTrustDomainId = domain,
                localModuleId = "M01",
            )
        if (stagedM01 !is EstablishmentEnrollmentAuthorityResult.Approved) {
            error("M01 staging approval rejected: $stagedM01")
        }
        val syntheticState = syntheticStateFromDraft(stagedM01.draft)

        val draftM01 = approvePeer(m02Enrollment, syntheticState, taskProfileRevision, domain, "M01")
        val draftM02 = approvePeer(m02Enrollment, syntheticState, taskProfileRevision, domain, "M02")

        val outputs = writeSignedDeliveries(batchDir, listOf("M01" to draftM01, "M02" to draftM02))
        val m01Version =
            draftM01.establishmentBindings.first { it.moduleId == "M01" }.establishmentKeyVersion
        val m02Version =
            draftM01.establishmentBindings.first { it.moduleId == "M02" }.establishmentKeyVersion
        File(batchDir, "PROVISIONING_MANIFEST.txt").writeText(
            buildManifest(
                domain = domain,
                taskProfileRevision = taskProfileRevision,
                modules =
                    listOf(
                        ManifestModule("M01", m01Version, m01.publicKeyFingerprintSha256Hex, m01.keyAlias),
                        ManifestModule("M02", m02Version, m02.publicKeyFingerprintSha256Hex, m02.keyAlias),
                    ),
            ),
        )
        return AuthorityResult(true, "signed duo deliveries written", outputs)
    }

    private fun runTrio(
        batchDir: File,
        m01: Evidence,
        m02: Evidence,
        thirdPeer: Evidence,
        taskProfileRevision: Long,
        domain: String,
    ): AuthorityResult {
        require(taskProfileRevision > 2L) { "taskProfileRevision must be > 2 for trio provisioning" }
        val thirdModuleId = thirdPeer.moduleId
        val revisionM01 = taskProfileRevision - 2L
        val revisionM02 = taskProfileRevision - 1L

        val stagedM01 =
            approvePeer(
                toEnrollmentEvidence(m01),
                currentState = null,
                taskProfileRevision = revisionM01,
                domain = domain,
                localModuleId = "M01",
            )
        var state = syntheticStateFromDraft(stagedM01)
        val withM02 =
            approvePeer(
                toEnrollmentEvidence(m02),
                currentState = state,
                taskProfileRevision = revisionM02,
                domain = domain,
                localModuleId = "M01",
            )
        state = syntheticStateFromDraft(withM02)
        val withThirdPeer =
            approvePeer(
                toEnrollmentEvidence(thirdPeer),
                currentState = state,
                taskProfileRevision = taskProfileRevision,
                domain = domain,
                localModuleId = "M01",
            )
        val sharedBindings = withThirdPeer.establishmentBindings
        val drafts =
            listOf("M01", "M02", thirdModuleId).map { moduleId ->
                moduleId to
                    withThirdPeer.copy(
                        localModuleId = moduleId,
                        establishmentBindings = sharedBindings,
                    )
            }
        val outputs = writeSignedDeliveries(batchDir, drafts)
        File(batchDir, "PROVISIONING_MANIFEST.txt").writeText(
            buildManifest(
                domain = domain,
                taskProfileRevision = taskProfileRevision,
                modules =
                    listOf(
                        ManifestModule(
                            "M01",
                            bindingVersion(sharedBindings, "M01"),
                            m01.publicKeyFingerprintSha256Hex,
                            m01.keyAlias,
                        ),
                        ManifestModule(
                            "M02",
                            bindingVersion(sharedBindings, "M02"),
                            m02.publicKeyFingerprintSha256Hex,
                            m02.keyAlias,
                        ),
                        ManifestModule(
                            thirdModuleId,
                            bindingVersion(sharedBindings, thirdModuleId),
                            thirdPeer.publicKeyFingerprintSha256Hex,
                            thirdPeer.keyAlias,
                        ),
                    ),
            ),
        )
        return AuthorityResult(true, "signed trio deliveries written", outputs)
    }

    private fun resolveThirdPeerEvidenceFile(batchDir: File): File? {
        val m03 = File(batchDir, "M03-evidence.txt")
        if (m03.exists()) return m03
        val m04 = File(batchDir, "M04-evidence.txt")
        if (m04.exists()) return m04
        return null
    }

    private data class ManifestModule(
        val moduleId: String,
        val establishmentKeyVersion: Long,
        val publicKeyFingerprint: String,
        val keystoreAlias: String,
    )

    private fun bindingVersion(
        bindings: List<EstablishmentProfileBinding>,
        moduleId: String,
    ): Long = bindings.first { it.moduleId == moduleId }.establishmentKeyVersion

    private fun buildManifest(
        domain: String,
        taskProfileRevision: Long,
        modules: List<ManifestModule>,
    ): String =
        buildString {
            appendLine("# AUTHORITY OUTPUT — do not copy from EP_PROBE")
            appendLine("operator_run_id=ep-operator-batch-${System.currentTimeMillis()}")
            appendLine("provisioned_at=${java.time.Instant.now()}")
            appendLine("deploymentTrustDomainId=$domain")
            appendLine("taskProfileRevision=$taskProfileRevision")
            appendLine("signerKeyVersion=1")
            appendLine("provisioning_topology=${if (modules.size == 3) "trio" else "duo"}")
            modules.forEach { module ->
                appendLine("${module.moduleId}.establishmentKeyVersion=${module.establishmentKeyVersion}")
                appendLine("${module.moduleId}.publicKeyFingerprint=${module.publicKeyFingerprint}")
                appendLine("${module.moduleId}.keystoreAlias=${module.keystoreAlias}")
            }
        }

    private fun writeSignedDeliveries(
        batchDir: File,
        drafts: List<Pair<String, EstablishmentEnrollmentRevisionDraft>>,
    ): Map<String, File> {
        val outputs = mutableMapOf<String, File>()
        for ((moduleId, draft) in drafts) {
            val protected =
                EstablishmentProfileRevisionCanonicalCodec.encode(
                    EstablishmentEnrollmentAuthorityHelper.draftToPayload(draft),
                )
            val signed = OperationalProfileTestSigning.signProtectedRevision(protected)
            val out = File(batchDir, "establishment-signed-$moduleId.bin")
            out.writeBytes(signed)
            outputs[moduleId] = out
        }
        return outputs
    }

    private fun approvePeer(
        enrollment: EstablishmentEnrollmentEvidence,
        currentState: AcceptedLocalEstablishmentTrustState?,
        taskProfileRevision: Long,
        domain: String,
        localModuleId: String,
    ): EstablishmentEnrollmentRevisionDraft {
        val approved =
            EstablishmentEnrollmentAuthorityHelper.approveEnrollment(
                request =
                    EstablishmentEnrollmentAuthorityRequest(
                        operatorAuthorized = true,
                        targetModuleId = enrollment.moduleId,
                        evidence = enrollment,
                        trigger = EstablishmentEnrollmentTrigger.OPERATOR_INITIAL_ENROLLMENT,
                    ),
                currentState = currentState,
                nextTaskProfileRevision = taskProfileRevision,
                deploymentTrustDomainId = domain,
                localModuleId = localModuleId,
            )
        if (approved !is EstablishmentEnrollmentAuthorityResult.Approved) {
            error("enrollment approval rejected for ${enrollment.moduleId} on $localModuleId: $approved")
        }
        return approved.draft
    }

    private fun syntheticStateFromDraft(
        draft: EstablishmentEnrollmentRevisionDraft,
    ): AcceptedLocalEstablishmentTrustState {
        val revision =
            AuthenticatedEstablishmentProfileRevision.forValidatedSemantics(
                EstablishmentEnrollmentAuthorityHelper.draftToPayload(draft),
            )
        return AcceptedLocalEstablishmentTrustState(
            taskProfileRevision = draft.taskProfileRevision,
            revisionIdentity = revision.revisionIdentity.copyOf(),
            localModuleId = draft.localModuleId,
            bindingsByKey =
                draft.establishmentBindings
                    .map { binding ->
                        ModuleEstablishmentBinding(
                            moduleId = binding.moduleId,
                            establishmentKeyVersion = binding.establishmentKeyVersion,
                            keyState = binding.keyState,
                            establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
                            activatedAtRevision = binding.activatedAtRevision,
                            retiredVerifyAtRevision = binding.retiredVerifyAtRevision,
                        )
                    }.associateBy {
                        AcceptedLocalEstablishmentTrustState.BindingKey(
                            it.moduleId,
                            it.establishmentKeyVersion,
                        )
                    },
            deploymentTrustDomainId = draft.deploymentTrustDomainId,
        )
    }

    private fun toEnrollmentEvidence(evidence: Evidence): EstablishmentEnrollmentEvidence =
        EstablishmentEnrollmentEvidence(
            moduleId = evidence.moduleId,
            algorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
            publicKeySpki = evidence.publicKeySpki.copyOf(),
            publicKeyFingerprintSha256Hex = evidence.publicKeyFingerprintSha256Hex,
            keyAlias = evidence.keyAlias,
            localKeyGeneration = evidence.localKeyGeneration,
        )

    fun loadEvidence(file: File): Evidence {
        require(file.exists()) { "missing evidence file: ${file.absolutePath}" }
        val map =
            file.readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }.toMap()
        return Evidence(
            moduleId = map.getValue("moduleId"),
            publicKeyFingerprintSha256Hex = map.getValue("publicKeyFingerprintSha256Hex"),
            keyAlias = map.getValue("keyAlias"),
            localKeyGeneration = map.getValue("localKeyGeneration").toLong(),
            publicKeySpki = Base64.getDecoder().decode(map.getValue("publicKeySpkiBase64")),
            operatorAssignedEstablishmentKeyVersion =
                map.getValue("operatorAssignedEstablishmentKeyVersion").toLong(),
        )
    }
}
