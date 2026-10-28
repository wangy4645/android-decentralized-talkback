package com.talkback.appprod.fieldtest

import android.content.Context
import android.util.Base64
import android.util.Log
import com.talkback.core.session.gbc.trust.profile.establishment.AndroidKeystoreLocalEstablishmentIdentityStore
import com.talkback.core.session.gbc.trust.profile.establishment.Ep1RemoteLookupOutcome
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentEnrollmentEvidenceProducer
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentFieldProbe
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionDeliveryAcceptResult
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreLoadResult
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult
import com.talkback.core.session.gbc.wiring.EstablishmentFieldProbeRuntime
import com.talkback.core.session.gbc.wiring.EstablishmentProductionComposition
import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition
import java.io.File
import java.security.MessageDigest

object EpEstablishmentOperatorProvisionSupport {
    private const val TAG = "Talkback"
    private const val LOG_PREFIX = "EP_OP_PROBE"
    private const val EVIDENCE_FILE = "EP_OPERATOR_EVIDENCE.txt"
    private const val ACCEPT_FILE = "EP_OPERATOR_ACCEPT.txt"
    private const val PENDING_DELIVERY_FILE = "establishment-signed-delivery-pending.bin"

    data class PreCheckResult(
        val success: Boolean,
        val moduleId: String,
        val message: String,
    )

    fun provisionKeystoreAndEmitEvidence(
        context: Context,
        establishmentKeyVersion: Long,
    ): PreCheckResult {
        val moduleId = FieldTestProvisioningSupport.readConfiguredModuleId(context)
        val trustDir = context.filesDir.resolve("gbc-trust").also { it.mkdirs() }
        val keystore = AndroidKeystoreLocalEstablishmentIdentityStore()
        val provision =
            when (
                val result = keystore.provisionFirstIdentity(moduleId, establishmentKeyVersion)
            ) {
                is LocalEstablishmentKeystoreProvisionResult.Created ->
                    result.identity
                is LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned ->
                    result.identity
                is LocalEstablishmentKeystoreProvisionResult.Failed ->
                    return fail(moduleId, "PROVISION_EVIDENCE", "FAILED", result.reason)
            }
        when (
            val produced =
                EstablishmentEnrollmentEvidenceProducer.produce(
                    provision,
                    localKeyGeneration = establishmentKeyVersion,
                )
        ) {
            is com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentEnrollmentEvidenceProduceResult.Ready -> {
                val evidence = produced.evidence
                val lines =
                    linkedMapOf(
                        "moduleId" to evidence.moduleId,
                        "algorithm" to evidence.algorithm.name,
                        "publicKeyFingerprintSha256Hex" to evidence.publicKeyFingerprintSha256Hex,
                        "keyAlias" to evidence.keyAlias,
                        "localKeyGeneration" to evidence.localKeyGeneration.toString(),
                        "publicKeySpkiBase64" to
                            Base64.encodeToString(evidence.publicKeySpki, Base64.NO_WRAP),
                        "operatorAssignedEstablishmentKeyVersion" to establishmentKeyVersion.toString(),
                    )
                val evidenceBody = lines.entries.joinToString("\n") { "${it.key}=${it.value}" }
                File(trustDir, EVIDENCE_FILE).writeText(evidenceBody)
                FieldTestInstrumentationTransport.emitPayload(
                    FieldTestInstrumentationTransport.KIND_EP_OPERATOR_EVIDENCE,
                    evidenceBody,
                )
                log(
                    "action=PROVISION_EVIDENCE moduleId=$moduleId establishmentKeyVersion=$establishmentKeyVersion " +
                        "fingerprint=${evidence.publicKeyFingerprintSha256Hex} outcome=READY",
                )
                return PreCheckResult(true, moduleId, "READY")
            }
            is com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentEnrollmentEvidenceProduceResult.Rejected ->
                return fail(moduleId, "PROVISION_EVIDENCE", "FAILED", produced.reason)
        }
    }

    fun writeSignedEstablishmentDeliveryFromBase64(
        context: Context,
        signedDeliveryBase64: String,
    ): PreCheckResult {
        val moduleId = FieldTestProvisioningSupport.readConfiguredModuleId(context)
        if (signedDeliveryBase64.isBlank()) {
            return fail(moduleId, "WRITE_DELIVERY", "FAILED", "signedDeliveryBase64 required")
        }
        val bytes =
            runCatching { Base64.decode(signedDeliveryBase64, Base64.DEFAULT) }
                .getOrElse {
                    return fail(moduleId, "WRITE_DELIVERY", "FAILED", "invalid base64")
                }
        if (bytes.isEmpty()) {
            return fail(moduleId, "WRITE_DELIVERY", "FAILED", "empty delivery")
        }
        val trustDir = context.filesDir.resolve("gbc-trust").also { it.mkdirs() }
        File(trustDir, PENDING_DELIVERY_FILE).writeBytes(bytes)
        log("action=WRITE_DELIVERY moduleId=$moduleId bytes=${bytes.size} outcome=READY")
        return PreCheckResult(true, moduleId, "READY")
    }

    fun acceptEstablishmentRevision(context: Context): PreCheckResult {
        val moduleId = FieldTestProvisioningSupport.readConfiguredModuleId(context)
        val trustDir = context.filesDir.resolve("gbc-trust")
        val pending = File(trustDir, PENDING_DELIVERY_FILE)
        if (!pending.isFile) {
            return fail(moduleId, "ACCEPT_REVISION", "FAILED", "pending delivery missing")
        }
        val deliveryBytes = pending.readBytes()
        val runtime = establishmentRuntime(context, trustDir)
        when (val accepted = runtime.establishmentProfileDeliveryAcceptor.acceptSignedDelivery(deliveryBytes)) {
            is EstablishmentProfileRevisionDeliveryAcceptResult.Accepted -> {
                val body =
                    buildString {
                        appendLine("moduleId=$moduleId")
                        appendLine("outcome=Accepted")
                        appendLine("idempotent=${accepted.idempotent}")
                        appendLine("taskProfileRevision=${accepted.state.taskProfileRevision}")
                        appendLine("deploymentTrustDomainId=${accepted.state.deploymentTrustDomainId}")
                    }
                File(trustDir, ACCEPT_FILE).writeText(body)
                pending.delete()
                log(
                    "action=ACCEPT_REVISION moduleId=$moduleId outcome=Accepted " +
                        "taskProfileRevision=${accepted.state.taskProfileRevision}",
                )
                return PreCheckResult(true, moduleId, "Accepted")
            }
            is EstablishmentProfileRevisionDeliveryAcceptResult.AuthenticationRejected ->
                return fail(moduleId, "ACCEPT_REVISION", "AuthenticationRejected", accepted.reason)
            EstablishmentProfileRevisionDeliveryAcceptResult.RollbackRejected ->
                return fail(moduleId, "ACCEPT_REVISION", "RollbackRejected", "rollback rejected")
            EstablishmentProfileRevisionDeliveryAcceptResult.RevisionConflictRejected ->
                return fail(moduleId, "ACCEPT_REVISION", "RevisionConflictRejected", "revision conflict")
            is EstablishmentProfileRevisionDeliveryAcceptResult.SemanticRejected ->
                return fail(moduleId, "ACCEPT_REVISION", "SemanticRejected", accepted.reason)
            is EstablishmentProfileRevisionDeliveryAcceptResult.PersistenceFailed ->
                return fail(moduleId, "ACCEPT_REVISION", "PersistenceFailed", accepted.reason)
        }
    }

    fun probeEstablishmentStorePresent(context: Context): PreCheckResult {
        val moduleId = FieldTestProvisioningSupport.readConfiguredModuleId(context)
        val artifact = FieldTestInstrumentationTransport.STORE_ARTIFACT_ESTABLISHMENT
        val file = File(context.filesDir, "gbc-trust/$artifact")
        val present = file.isFile
        val payload = "artifact=$artifact\npresent=$present\n"
        FieldTestInstrumentationTransport.emitPayload(
            FieldTestInstrumentationTransport.KIND_STORE_SANITY,
            payload,
        )
        log(
            "action=PRE1_STORE_PROBE moduleId=$moduleId artifact=$artifact present=$present " +
                "outcome=${if (present) "PRESENT" else "ABSENT"}",
        )
        return PreCheckResult(
            success = true,
            moduleId = moduleId,
            message = if (present) "PRESENT" else "ABSENT",
        )
    }

    fun verifyLocalKeystoreIdentity(
        context: Context,
        establishmentKeyVersion: Long,
        expectedFingerprint: String,
    ): PreCheckResult {
        val moduleId = FieldTestProvisioningSupport.readConfiguredModuleId(context)
        val keystore = AndroidKeystoreLocalEstablishmentIdentityStore()
        when (
            val loaded = keystore.loadIdentity(moduleId, establishmentKeyVersion)
        ) {
            is LocalEstablishmentKeystoreLoadResult.Ready -> {
                val actual = sha256Hex(loaded.identity.publicKeySpki)
                val expected = expectedFingerprint.trim().lowercase()
                val match = actual.equals(expected, ignoreCase = true)
                log(
                    "action=PRE2_KEYSTORE moduleId=$moduleId establishmentKeyVersion=$establishmentKeyVersion " +
                        "expected=$expected actual=$actual outcome=${if (match) "PASS" else "FINGERPRINT_MISMATCH"}",
                )
                return PreCheckResult(match, moduleId, if (match) "PASS" else "FINGERPRINT_MISMATCH")
            }
            is LocalEstablishmentKeystoreLoadResult.Unavailable ->
                return fail(moduleId, "PRE2_KEYSTORE", "FAILED", loaded.reason)
        }
    }

    fun pre4RemoteLookup(
        context: Context,
        peerModuleId: String,
        establishmentKeyVersion: Long,
    ): PreCheckResult {
        val moduleId = FieldTestProvisioningSupport.readConfiguredModuleId(context)
        val trustDir = context.filesDir.resolve("gbc-trust").toPath()
        val session =
            EstablishmentFieldProbeRuntime.fromProductionTrustDir(
                deploymentTrustDomainId = GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN,
                trustDir = trustDir,
                localModuleId = moduleId,
            )
        val outcome =
            EstablishmentFieldProbe.logEp1RemoteLookup(
                session = session,
                peerModuleId = peerModuleId.trim().uppercase(),
                establishmentKeyVersion = establishmentKeyVersion,
                log = { line -> Log.i(TAG, line) },
            )
        val success = outcome is Ep1RemoteLookupOutcome.Found
        log(
            "action=PRE4_REMOTE_LOOKUP moduleId=$moduleId peerModuleId=$peerModuleId " +
                "establishmentKeyVersion=$establishmentKeyVersion outcome=${outcome.wireName}",
        )
        return PreCheckResult(success, moduleId, outcome.wireName)
    }

    private fun establishmentRuntime(
        context: Context,
        trustDir: File,
    ): EstablishmentProductionComposition.EstablishmentProductionRuntime {
        val moduleId = FieldTestProvisioningSupport.readConfiguredModuleId(context)
        val gbcRuntime =
            GbcProductionTrustComposition.create(
                deploymentTrustDomainId = GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN,
                anchorFile = trustDir.resolve("operational-trust-anchor.bin").toPath(),
                trustStateFile = trustDir.resolve("accepted-local-trust-state.bin").toPath(),
            )
        return EstablishmentProductionComposition.create(
            deploymentTrustDomainId = GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN,
            anchorSource = gbcRuntime.anchorSource,
            localModuleId = moduleId,
            establishmentStateFile =
                trustDir.resolve("accepted-local-establishment-trust-state.bin").toPath(),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private fun log(detail: String) {
        Log.i(TAG, "$LOG_PREFIX $detail")
    }

    private fun fail(
        moduleId: String,
        action: String,
        outcome: String,
        reason: String,
    ): PreCheckResult {
        log("action=$action moduleId=$moduleId outcome=$outcome reason=$reason")
        return PreCheckResult(false, moduleId, "$outcome: $reason")
    }
}
