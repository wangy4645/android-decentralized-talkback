package com.talkback.appprod.fieldtest

import android.content.Context
import android.util.Log
import com.talkback.appprod.data.AppConfigStore
import com.talkback.core.session.gbc.issuance.GenerationFactSignerEstablisher
import com.talkback.core.session.gbc.issuance.GenerationFactSignerEstablishResult
import com.talkback.core.session.gbc.issuance.GenerationFactSignerFingerprint
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

data class FieldTestProvisioningResult(
    val success: Boolean,
    val localModuleId: String,
    val deploymentTrustDomainId: String,
    val establishmentRecordIdentity: String,
    val gfSignerRecordIdentity: String,
    val fingerprintMatch: Boolean,
    val bindingFingerprints: Map<String, String>,
    val runtimeSignerFingerprints: Map<String, String>,
    val message: String,
)

object FieldTestProvisioningSupport {
    private const val TAG = "Talkback"
    private const val TRUST_DOMAIN = GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN
    private const val ESTABLISHMENT_RECORD_ID = "adr0057-field-test-establishment-v1"
    private const val GF_SIGNER_RECORD_ID = "adr0057-field-test-gf-signer-v1"
    private const val TASK_PROFILE_REVISION = 10L
    private const val SIGNER_KEY_VERSION = 1L
    private const val ACTIVATED_AT_REVISION = 10L
    private val MODULE_IDS = listOf("M01", "M02", "M03", "M04", "M05")

    fun readConfiguredModuleId(context: Context): String =
        AppConfigStore(context).load().moduleId.trim().uppercase()

    fun provision(context: Context): FieldTestProvisioningResult {
        val localModuleId = readConfiguredModuleId(context)
        val trustDir = context.filesDir.resolve("gbc-trust").also { it.mkdirs() }
        resetTrustArtifacts(trustDir)

        val anchorFile = trustDir.resolve("operational-trust-anchor.bin")
        val trustStateFile = trustDir.resolve("accepted-local-trust-state.bin")
        val signerPersistence = GbcFieldEstablishedSignerSupport.signerPersistence(trustDir)

        val record =
            EstablishmentRecord(
                deploymentTrustDomainId = TRUST_DOMAIN,
                operationalAuthorityPublicKeySpki = FieldTestOperationalAuthority.publicKeySpki.copyOf(),
                establishmentRecordIdentity = ESTABLISHMENT_RECORD_ID,
                provenanceClass = EstablishmentProvenanceClass.OPERATOR_OUT_OF_BAND,
            )
        val auth =
            PapT2EstablishmentAuthorizationIssuer().authorize(record)
                ?: return failure(localModuleId, "PAP-T2 authorization rejected")
        val anchorPersistence = FileOperationalTrustAnchorPersistence(anchorFile.toPath())
        when (val anchorResult = OperationalAnchorEstablisher(anchorPersistence).establish(auth)) {
            is EstablishmentResult.Established,
            is EstablishmentResult.AlreadyEstablished,
            -> Unit
            is EstablishmentResult.Rejected ->
                return failure(localModuleId, "anchor establishment rejected: ${anchorResult.reason}")
        }

        when (
            val signerResult =
                GenerationFactSignerEstablisher(signerPersistence).establish(
                    FieldTestGenerationFactAuthority.pkcs8PrivateKey,
                    FieldTestGenerationFactAuthority.publicKeySpki,
                    GF_SIGNER_RECORD_ID,
                )
        ) {
            is GenerationFactSignerEstablishResult.Established,
            is GenerationFactSignerEstablishResult.AlreadyEstablished,
            -> Unit
            is GenerationFactSignerEstablishResult.Rejected ->
                return failure(localModuleId, "GF signer establishment rejected: ${signerResult.reason}")
        }

        val runtime =
            GbcProductionTrustComposition.create(
                deploymentTrustDomainId = TRUST_DOMAIN,
                anchorFile = anchorFile.toPath(),
                trustStateFile = trustStateFile.toPath(),
            )
        val payload =
            GenerationFactProfileTrustPayload(
                taskProfileRevision = TASK_PROFILE_REVISION,
                localModuleId = localModuleId,
                deploymentTrustDomainId = TRUST_DOMAIN,
                moduleBindings =
                    MODULE_IDS.map { moduleId ->
                        ModuleSigningBinding(
                            moduleId = moduleId,
                            signerKeyVersion = SIGNER_KEY_VERSION,
                            keyState = GenerationFactKeyState.ACTIVE,
                            publicKeySpki = FieldTestGenerationFactAuthority.publicKeySpki.copyOf(),
                            activatedAtRevision = ACTIVATED_AT_REVISION,
                        )
                    },
            )
        val signed = FieldTestOperationalAuthority.signPayloadV2(payload)
        when (val accepted = runtime.profileAcceptor.acceptProtectedRevision(signed)) {
            is ProfileRevisionAcceptResult.Accepted -> Unit
            is ProfileRevisionAcceptResult.AuthenticationRejected ->
                return failure(localModuleId, "profile auth rejected: ${accepted.reason}")
            is ProfileRevisionAcceptResult.RevisionConflictRejected ->
                return failure(localModuleId, "profile revision conflict")
            is ProfileRevisionAcceptResult.RollbackRejected ->
                return failure(localModuleId, "profile rollback rejected")
            is ProfileRevisionAcceptResult.SemanticRejected ->
                return failure(localModuleId, "profile semantic rejected: ${accepted.reason}")
            is ProfileRevisionAcceptResult.PersistenceFailed ->
                return failure(localModuleId, "profile persistence failed: ${accepted.reason}")
        }

        val runtimeSigner = GbcFieldEstablishedSignerSupport.loadSigner(trustDir)
            ?: return failure(localModuleId, "runtime signer missing after establish")
        val runtimeFp = GenerationFactSignerFingerprint.spkiSha256Hex(runtimeSigner.publicKeySpki)
        val bindingFingerprints = linkedMapOf<String, String>()
        val runtimeFingerprints = linkedMapOf<String, String>()
        var fingerprintMatch = true
        val snapshot = runtime.trustStore.currentSnapshot()
            ?: return failure(localModuleId, "accepted trust snapshot missing")
        for (moduleId in MODULE_IDS) {
            val binding =
                snapshot.bindingsByKey.values.firstOrNull {
                    it.moduleId == moduleId && it.signerKeyVersion == SIGNER_KEY_VERSION
                } ?: return failure(localModuleId, "binding missing for $moduleId")
            val bindingFp = GenerationFactSignerFingerprint.spkiSha256Hex(binding.publicKeySpki)
            bindingFingerprints[moduleId] = bindingFp
            runtimeFingerprints[moduleId] = runtimeFp
            if (bindingFp != runtimeFp) {
                fingerprintMatch = false
            }
        }

        val result =
            FieldTestProvisioningResult(
                success = fingerprintMatch,
                localModuleId = localModuleId,
                deploymentTrustDomainId = TRUST_DOMAIN,
                establishmentRecordIdentity = ESTABLISHMENT_RECORD_ID,
                gfSignerRecordIdentity = GF_SIGNER_RECORD_ID,
                fingerprintMatch = fingerprintMatch,
                bindingFingerprints = bindingFingerprints,
                runtimeSignerFingerprints = runtimeFingerprints,
                message = if (fingerprintMatch) "FTPH PASS" else "FTPH FAIL fingerprint mismatch",
            )
        writeResultArtifact(trustDir, result)
        Log.i(TAG, "FTPH moduleId=$localModuleId outcome=${if (result.success) "PASS" else "FAIL"}")
        return result
    }

    private fun resetTrustArtifacts(trustDir: File) {
        trustDir.listFiles()?.forEach { entry -> entry.delete() }
    }

    private fun failure(localModuleId: String, message: String): FieldTestProvisioningResult {
        val result =
            FieldTestProvisioningResult(
                success = false,
                localModuleId = localModuleId,
                deploymentTrustDomainId = TRUST_DOMAIN,
                establishmentRecordIdentity = ESTABLISHMENT_RECORD_ID,
                gfSignerRecordIdentity = GF_SIGNER_RECORD_ID,
                fingerprintMatch = false,
                bindingFingerprints = emptyMap(),
                runtimeSignerFingerprints = emptyMap(),
                message = message,
            )
        Log.i(TAG, "FTPH moduleId=$localModuleId outcome=FAIL reason=$message")
        return result
    }

    private fun buildFtphResultBody(result: FieldTestProvisioningResult): String {
        val lines = buildList {
            add(FieldTestOperationalAuthority.BANNER)
            add("success=${result.success}")
            add("localModuleId=${result.localModuleId}")
            add("deploymentTrustDomainId=${result.deploymentTrustDomainId}")
            add("establishmentRecordIdentity=${result.establishmentRecordIdentity}")
            add("gfSignerRecordIdentity=${result.gfSignerRecordIdentity}")
            add("fingerprintMatch=${result.fingerprintMatch}")
            result.bindingFingerprints.forEach { (moduleId, fp) ->
                add("acceptedBinding.$moduleId.signerKeyVersion=$SIGNER_KEY_VERSION")
                add("acceptedBinding.$moduleId.fingerprint=$fp")
                add("runtimeSigner.$moduleId.fingerprint=${result.runtimeSignerFingerprints[moduleId]}")
                val match =
                    result.runtimeSignerFingerprints[moduleId] == fp && result.fingerprintMatch
                add("runtimeSigner.$moduleId.match=$match")
            }
            add("message=${result.message}")
        }
        return lines.joinToString("\n")
    }

    private fun writeResultArtifact(trustDir: File, result: FieldTestProvisioningResult) {
        val body = buildFtphResultBody(result)
        File(trustDir, "FTPH_RESULT.txt").writeText(body)
        if (result.success) {
            FieldTestInstrumentationTransport.emitPayload(
                FieldTestInstrumentationTransport.KIND_FTPH_RESULT,
                body,
            )
        }
    }
}
