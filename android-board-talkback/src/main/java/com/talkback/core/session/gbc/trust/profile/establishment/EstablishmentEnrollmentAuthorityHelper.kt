package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.GenerationFactKeyState

/**
 * Operator-side enrollment request. Device evidence does not self-authorize.
 */
data class EstablishmentEnrollmentAuthorityRequest(
    val operatorAuthorized: Boolean,
    val targetModuleId: String,
    val evidence: EstablishmentEnrollmentEvidence,
    val trigger: EstablishmentEnrollmentTrigger,
)

data class EstablishmentEnrollmentRevisionDraft(
    val taskProfileRevision: Long,
    val localModuleId: String,
    val deploymentTrustDomainId: String,
    val establishmentBindings: List<EstablishmentProfileBinding>,
    val assignedEstablishmentKeyVersion: Long,
)

sealed class EstablishmentEnrollmentAuthorityResult {
    data class Approved(
        val draft: EstablishmentEnrollmentRevisionDraft,
    ) : EstablishmentEnrollmentAuthorityResult()

    data class Rejected(
        val reason: String,
    ) : EstablishmentEnrollmentAuthorityResult()
}

/**
 * Assigns establishment versions and builds Profile establishment revision drafts.
 *
 * Not a provisioning authority on device — operator/provisioning authority only.
 * Never rotates on runtime mismatch.
 */
object EstablishmentEnrollmentAuthorityHelper {
    fun approveEnrollment(
        request: EstablishmentEnrollmentAuthorityRequest,
        currentState: AcceptedLocalEstablishmentTrustState?,
        nextTaskProfileRevision: Long,
        deploymentTrustDomainId: String,
        localModuleId: String,
    ): EstablishmentEnrollmentAuthorityResult {
        if (!request.operatorAuthorized) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("operator not authorized")
        }
        if (request.targetModuleId.isBlank()) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("targetModuleId required")
        }
        if (request.targetModuleId != request.evidence.moduleId) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("forged moduleId")
        }
        if (deploymentTrustDomainId.isBlank()) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("deploymentTrustDomainId required")
        }
        if (localModuleId.isBlank()) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("localModuleId required")
        }
        if (nextTaskProfileRevision <= (currentState?.taskProfileRevision ?: 0L)) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("taskProfileRevision must advance")
        }
        val roundTrip = EstablishmentEnrollmentEvidenceCanonicalCodec.decode(
            EstablishmentEnrollmentEvidenceCanonicalCodec.encode(request.evidence),
        )
        if (roundTrip == null ||
            roundTrip.moduleId != request.evidence.moduleId ||
            !roundTrip.publicKeySpki.contentEquals(request.evidence.publicKeySpki)
        ) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("malformed enrollment evidence")
        }
        if (request.evidence.publicKeyFingerprintSha256Hex !=
            request.evidence.publicKeySpki.sha256Hex()
        ) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("fingerprint mismatch")
        }
        if (!EstablishmentSpkiValidator.isSupportedRsa3072(request.evidence.publicKeySpki)) {
            return EstablishmentEnrollmentAuthorityResult.Rejected("unsupported public key")
        }

        val versionAssignment =
            assignEstablishmentKeyVersion(
                currentState = currentState,
                evidence = request.evidence,
                trigger = request.trigger,
            )
        when (versionAssignment) {
            is VersionAssignment.Rejected ->
                return EstablishmentEnrollmentAuthorityResult.Rejected(versionAssignment.reason)
            is VersionAssignment.Assigned -> {
                val binding =
                    EstablishmentProfileBinding(
                        moduleId = request.evidence.moduleId,
                        establishmentAlgorithm = request.evidence.algorithm,
                        establishmentKeyVersion = versionAssignment.establishmentKeyVersion,
                        keyState = GenerationFactKeyState.ACTIVE,
                        establishmentPublicKeySpki = request.evidence.publicKeySpki.copyOf(),
                        activatedAtRevision = nextTaskProfileRevision,
                    )
                val bindings =
                    mergeBindings(
                        currentState = currentState,
                        updatedModuleId = request.evidence.moduleId,
                        updatedBinding = binding,
                    )
                return EstablishmentEnrollmentAuthorityResult.Approved(
                    EstablishmentEnrollmentRevisionDraft(
                        taskProfileRevision = nextTaskProfileRevision,
                        localModuleId = localModuleId,
                        deploymentTrustDomainId = deploymentTrustDomainId,
                        establishmentBindings = bindings,
                        assignedEstablishmentKeyVersion = versionAssignment.establishmentKeyVersion,
                    ),
                )
            }
        }
    }

    fun draftToPayload(draft: EstablishmentEnrollmentRevisionDraft): EstablishmentProfileTrustPayload =
        EstablishmentProfileTrustPayload(
            taskProfileRevision = draft.taskProfileRevision,
            localModuleId = draft.localModuleId,
            establishmentBindings = draft.establishmentBindings,
            deploymentTrustDomainId = draft.deploymentTrustDomainId,
        )

    private sealed interface VersionAssignment {
        data class Assigned(
            val establishmentKeyVersion: Long,
        ) : VersionAssignment

        data class Rejected(
            val reason: String,
        ) : VersionAssignment
    }

    private fun assignEstablishmentKeyVersion(
        currentState: AcceptedLocalEstablishmentTrustState?,
        evidence: EstablishmentEnrollmentEvidence,
        trigger: EstablishmentEnrollmentTrigger,
    ): VersionAssignment {
        val moduleBindings =
            currentState?.bindingsByKey?.values?.filter { it.moduleId == evidence.moduleId }
                ?: emptyList()
        val maxVersion = moduleBindings.maxOfOrNull { it.establishmentKeyVersion } ?: 0L
        val matching =
            moduleBindings.firstOrNull {
                it.establishmentPublicKeySpki.contentEquals(evidence.publicKeySpki)
            }
        if (matching != null) {
            if (trigger == EstablishmentEnrollmentTrigger.OPERATOR_KEY_ROTATION &&
                matching.establishmentKeyVersion == maxVersion
            ) {
                return VersionAssignment.Rejected("idempotent key cannot consume rotation trigger")
            }
            return VersionAssignment.Assigned(matching.establishmentKeyVersion)
        }
        if (trigger == EstablishmentEnrollmentTrigger.IDEMPOTENT_REENROLLMENT) {
            return VersionAssignment.Rejected("unknown key for idempotent re-enrollment")
        }
        if (maxVersion > 0L && trigger != EstablishmentEnrollmentTrigger.OPERATOR_KEY_ROTATION) {
            return VersionAssignment.Rejected("new SPKI requires explicit operator key rotation")
        }
        val nextVersion = if (maxVersion == 0L) 1L else maxVersion + 1L
        return VersionAssignment.Assigned(nextVersion)
    }

    private fun mergeBindings(
        currentState: AcceptedLocalEstablishmentTrustState?,
        updatedModuleId: String,
        updatedBinding: EstablishmentProfileBinding,
    ): List<EstablishmentProfileBinding> {
        val latestByModule =
            currentState?.bindingsByKey?.values
                ?.groupBy { it.moduleId }
                ?.mapValues { (_, bindings) -> bindings.maxBy { it.establishmentKeyVersion } }
                ?.toMutableMap()
                ?: mutableMapOf()
        latestByModule[updatedModuleId] = updatedBinding.toModuleBinding()
        return latestByModule.values
            .map { it.toEstablishmentProfileBinding() }
            .sortedWith(compareBy({ it.moduleId }, { it.establishmentKeyVersion }))
    }

    private fun EstablishmentProfileBinding.toModuleBinding(): ModuleEstablishmentBinding =
        ModuleEstablishmentBinding(
            moduleId = moduleId,
            establishmentKeyVersion = establishmentKeyVersion,
            keyState = keyState,
            establishmentPublicKeySpki = establishmentPublicKeySpki.copyOf(),
            activatedAtRevision = activatedAtRevision,
            retiredVerifyAtRevision = retiredVerifyAtRevision,
        )

    private fun ModuleEstablishmentBinding.toEstablishmentProfileBinding(): EstablishmentProfileBinding =
        EstablishmentProfileBinding(
            moduleId = moduleId,
            establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
            establishmentKeyVersion = establishmentKeyVersion,
            keyState = keyState,
            establishmentPublicKeySpki = establishmentPublicKeySpki.copyOf(),
            activatedAtRevision = activatedAtRevision,
            retiredVerifyAtRevision = retiredVerifyAtRevision,
        )
}
