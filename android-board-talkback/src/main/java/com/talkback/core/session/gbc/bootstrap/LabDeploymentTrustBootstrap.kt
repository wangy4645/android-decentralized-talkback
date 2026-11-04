package com.talkback.core.session.gbc.bootstrap

import android.content.Context
import com.talkback.core.session.gbc.issuance.GenerationFactSignerEstablishResult
import com.talkback.core.session.gbc.issuance.GenerationFactSignerEstablisher
import com.talkback.core.session.gbc.issuance.GenerationFactSignerPersistenceState
import com.talkback.core.session.gbc.issuance.GbcFieldEstablishedSignerSupport
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.GenerationFactProfileTrustPayload
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptResult
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProvenanceClass
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentRecord
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentResult
import com.talkback.core.session.gbc.trust.profile.establishment.FileOperationalTrustAnchorPersistence
import com.talkback.core.session.gbc.trust.profile.establishment.OperationalAnchorEstablisher
import com.talkback.core.session.gbc.trust.profile.establishment.PapT2EstablishmentAuthorizationIssuer
import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition
import java.io.File

/**
 * Idempotent local trust bootstrap for release app (no adb instrument).
 * Material matches legacy field-test fleet so mesh FACT/CREATION keep working after reinstall.
 */
object LabDeploymentTrustBootstrap {
    private const val TRUST_DOMAIN = GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN
    private const val ESTABLISHMENT_RECORD_ID = "adr0057-field-test-establishment-v1"
    private const val GF_SIGNER_RECORD_ID = "adr0057-field-test-gf-signer-v1"
    private const val TASK_PROFILE_REVISION = 10L
    private const val SIGNER_KEY_VERSION = 1L
    private const val ACTIVATED_AT_REVISION = 10L
    private val FLEET_MODULE_IDS = listOf("M01", "M02", "M03", "M04", "M05")

    sealed class Outcome {
        data object AlreadyReady : Outcome()

        data class Established(val localModuleId: String) : Outcome()

        data class Failed(val reason: String) : Outcome()
    }

    fun isCreationSignerReady(
        context: Context,
        localModuleId: String,
    ): Boolean {
        val moduleId = localModuleId.trim().uppercase()
        if (moduleId.isBlank()) return false
        val trustDir = context.filesDir.resolve("gbc-trust")
        val persistence = GbcFieldEstablishedSignerSupport.signerPersistence(trustDir)
        if (persistence.currentState() !is GenerationFactSignerPersistenceState.Established) {
            return false
        }
        val trustStateFile = trustDir.resolve("accepted-local-trust-state.bin")
        if (!trustStateFile.isFile) return false
        val runtime =
            GbcProductionTrustComposition.create(
                deploymentTrustDomainId = TRUST_DOMAIN,
                anchorFile = trustDir.resolve("operational-trust-anchor.bin").toPath(),
                trustStateFile = trustStateFile.toPath(),
            )
        val snapshot = runtime.trustStore.currentSnapshot() ?: return false
        return snapshot.bindingsByKey.values.any { binding ->
            binding.moduleId == moduleId &&
                binding.keyState == GenerationFactKeyState.ACTIVE &&
                binding.signerKeyVersion > 0L
        }
    }

    fun ensureIfNeeded(
        context: Context,
        localModuleId: String,
        onLog: (String) -> Unit = {},
    ): Outcome {
        val moduleId = localModuleId.trim().uppercase()
        if (moduleId.isBlank()) {
            return Outcome.Failed("blank moduleId")
        }
        if (isCreationSignerReady(context, moduleId)) {
            onLog("DEPLOYMENT_TRUST already ready moduleId=$moduleId")
            return Outcome.AlreadyReady
        }
        onLog("DEPLOYMENT_TRUST bootstrap starting moduleId=$moduleId")
        val trustDir = context.filesDir.resolve("gbc-trust").also { it.mkdirs() }
        val anchorFile = trustDir.resolve("operational-trust-anchor.bin")
        val trustStateFile = trustDir.resolve("accepted-local-trust-state.bin")
        val signerPersistence = GbcFieldEstablishedSignerSupport.signerPersistence(trustDir)

        if (!anchorFile.isFile) {
            val record =
                EstablishmentRecord(
                    deploymentTrustDomainId = TRUST_DOMAIN,
                    operationalAuthorityPublicKeySpki =
                        DeploymentOperationalProfileSigning.publicKeySpki.copyOf(),
                    establishmentRecordIdentity = ESTABLISHMENT_RECORD_ID,
                    provenanceClass = EstablishmentProvenanceClass.OPERATOR_OUT_OF_BAND,
                )
            val auth =
                PapT2EstablishmentAuthorizationIssuer().authorize(record)
                    ?: return Outcome.Failed("PAP-T2 authorization rejected").also {
                        onLog("DEPLOYMENT_TRUST bootstrap FAIL: PAP-T2")
                    }
            when (
                val anchorResult =
                    OperationalAnchorEstablisher(
                        FileOperationalTrustAnchorPersistence(anchorFile.toPath()),
                    ).establish(auth)
            ) {
                is EstablishmentResult.Established,
                is EstablishmentResult.AlreadyEstablished,
                -> Unit
                is EstablishmentResult.Rejected ->
                    return Outcome.Failed("anchor: ${anchorResult.reason}").also {
                        onLog("DEPLOYMENT_TRUST bootstrap FAIL anchor=${anchorResult.reason}")
                    }
            }
        }

        when (
            val signerResult =
                GenerationFactSignerEstablisher(signerPersistence).establish(
                    DeploymentGenerationFactMaterial.pkcs8PrivateKey,
                    DeploymentGenerationFactMaterial.publicKeySpki,
                    GF_SIGNER_RECORD_ID,
                )
        ) {
            is GenerationFactSignerEstablishResult.Established,
            is GenerationFactSignerEstablishResult.AlreadyEstablished,
            -> Unit
            is GenerationFactSignerEstablishResult.Rejected ->
                return Outcome.Failed("signer: ${signerResult.reason}").also {
                    onLog("DEPLOYMENT_TRUST bootstrap FAIL signer=${signerResult.reason}")
                }
        }

        val runtime =
            GbcProductionTrustComposition.create(
                deploymentTrustDomainId = TRUST_DOMAIN,
                anchorFile = anchorFile.toPath(),
                trustStateFile = trustStateFile.toPath(),
            )
        if (!isCreationSignerReady(context, moduleId)) {
            val payload =
                GenerationFactProfileTrustPayload(
                    taskProfileRevision = TASK_PROFILE_REVISION,
                    localModuleId = moduleId,
                    deploymentTrustDomainId = TRUST_DOMAIN,
                    moduleBindings =
                        FLEET_MODULE_IDS.map { id ->
                            ModuleSigningBinding(
                                moduleId = id,
                                signerKeyVersion = SIGNER_KEY_VERSION,
                                keyState = GenerationFactKeyState.ACTIVE,
                                publicKeySpki = DeploymentGenerationFactMaterial.publicKeySpki.copyOf(),
                                activatedAtRevision = ACTIVATED_AT_REVISION,
                            )
                        },
                )
            val signed = DeploymentOperationalProfileSigning.signPayloadV2(payload)
            when (val accepted = runtime.profileAcceptor.acceptProtectedRevision(signed)) {
                is ProfileRevisionAcceptResult.Accepted -> Unit
                is ProfileRevisionAcceptResult.AuthenticationRejected ->
                    return Outcome.Failed("profile auth: ${accepted.reason}").also {
                        onLog("DEPLOYMENT_TRUST bootstrap FAIL profile auth")
                    }
                is ProfileRevisionAcceptResult.RevisionConflictRejected ->
                    return Outcome.Failed("profile revision conflict").also {
                        onLog("DEPLOYMENT_TRUST bootstrap FAIL profile conflict")
                    }
                is ProfileRevisionAcceptResult.RollbackRejected ->
                    return Outcome.Failed("profile rollback rejected").also {
                        onLog("DEPLOYMENT_TRUST bootstrap FAIL profile rollback")
                    }
                is ProfileRevisionAcceptResult.SemanticRejected ->
                    return Outcome.Failed("profile semantic: ${accepted.reason}").also {
                        onLog("DEPLOYMENT_TRUST bootstrap FAIL profile semantic")
                    }
                is ProfileRevisionAcceptResult.PersistenceFailed ->
                    return Outcome.Failed("profile persistence: ${accepted.reason}").also {
                        onLog("DEPLOYMENT_TRUST bootstrap FAIL profile persist")
                    }
            }
        }

        return if (isCreationSignerReady(context, moduleId)) {
            writeMarker(trustDir, moduleId, success = true)
            onLog("DEPLOYMENT_TRUST bootstrap OK moduleId=$moduleId")
            Outcome.Established(moduleId)
        } else {
            writeMarker(trustDir, moduleId, success = false, reason = "post-check failed")
            Outcome.Failed("post-establish check failed").also {
                onLog("DEPLOYMENT_TRUST bootstrap FAIL post-check")
            }
        }
    }

    private fun writeMarker(
        trustDir: File,
        moduleId: String,
        success: Boolean,
        reason: String = "",
    ) {
        val line =
            buildString {
                append("success=$success")
                append("\nlocalModuleId=$moduleId")
                append("\nsource=LabDeploymentTrustBootstrap")
                if (reason.isNotBlank()) append("\nreason=$reason")
            }
        trustDir.resolve("DEPLOYMENT_TRUST_BOOTSTRAP.txt").writeText(line)
    }
}
