package com.talkback.appprod.enrollment

import android.content.Context
import com.talkback.core.conference.session.profile01.wire.Profile01ProfileBackedRecipientEstablishmentKeyLookup
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore
import com.talkback.core.util.TalkbackLog
import com.talkback.core.session.gbc.wiring.EstablishmentProductionComposition
import java.util.concurrent.atomic.AtomicBoolean

class EstablishmentEnrollmentCoordinator(
    private val context: Context,
    private val moduleId: String,
    private val keystoreStore: LocalEstablishmentKeystoreIdentityStore,
    private val authorityPort: EnrollmentAuthorityPort = LabEnrollmentAuthorityPort(),
    private val onLog: (String) -> Unit = { TalkbackLog.i(it) },
) {
    private val acceptInFlight = AtomicBoolean(false)
    private val stateStore =
        EstablishmentEnrollmentCoordinatorStateStore(
            EstablishmentEnrollmentRuntimeFactory.trustDir(context)
                .resolve("enrollment-coordinator-state.properties"),
        )

    @Volatile
    private var productionRuntime: EstablishmentProductionComposition.EstablishmentProductionRuntime? = null

    @Volatile
    private var readinessGate: EstablishmentReadinessGate? = null

    @Volatile
    private var phase: EnrollmentCoordinatorPhase = EnrollmentCoordinatorPhase.LOCAL_IDENTITY_PENDING

    fun currentPhase(): EnrollmentCoordinatorPhase = phase

    fun readinessGateOrNull(): EstablishmentReadinessGate? = readinessGate

    fun sharedProductionRuntime(): EstablishmentProductionComposition.EstablishmentProductionRuntime? =
        productionRuntime

    fun isLocalEstablishmentReady(): Boolean =
        phase == EnrollmentCoordinatorPhase.LOCAL_ESTABLISHMENT_READY &&
            readinessGate?.localReadiness()?.ready == true

    fun onRuntimeStarting() {
        productionRuntime =
            EstablishmentEnrollmentRuntimeFactory.createProductionRuntime(
                context = context,
                moduleId = moduleId,
                keystoreStore = keystoreStore,
            )
        val runtime = productionRuntime ?: return
        readinessGate =
            EstablishmentReadinessGate(
                moduleId = moduleId,
                establishmentStore = runtime.establishmentTrustStore,
                keystoreStore = keystoreStore,
                peerLookup =
                    Profile01ProfileBackedRecipientEstablishmentKeyLookup(
                        runtime.establishmentTrustStore,
                    ),
            )
        runtime.establishmentTrustStore.reloadFromPersistence()
        resumeFromDisk()
    }

    fun onRuntimeStarted() {
        refreshLocalPhaseFromVerify(logTransition = false)
    }

    fun submitSignedDeliveryBase64(signedDeliveryBase64: String): SubmitResult {
        if (acceptInFlight.compareAndSet(false, true)) {
            try {
                return submitSignedDeliveryBase64Internal(signedDeliveryBase64)
            } finally {
                acceptInFlight.set(false)
            }
        }
        return SubmitResult.Busy
    }

    private fun submitSignedDeliveryBase64Internal(signedDeliveryBase64: String): SubmitResult {
        ensureLocalIdentityStep()
        if (phase == EnrollmentCoordinatorPhase.LOCAL_IDENTITY_FAILED) {
            return SubmitResult.Failed("E_KEYSTORE_PROVISION_FAILED", "local identity failed")
        }
        val runtime = productionRuntime ?: return SubmitResult.Failed("E_ENROLL_PERSIST_FAILED", "runtime not ready")
        val bytes =
            authorityPort.decodeSignedDeliveryBase64(signedDeliveryBase64).getOrElse { error ->
                return SubmitResult.Failed("E_ENROLL_SEMANTIC_REJECTED", error.message ?: "invalid base64")
            }
        phase = EnrollmentCoordinatorPhase.ENROLLMENT_SUBMITTED
        persistPhase()
        log("state=$phase action=accept_begin bytes=${bytes.size}")
        val acceptor = EstablishmentEnrollmentAcceptor(runtime.establishmentProfileDeliveryAcceptor)
        return when (val outcome = acceptor.acceptSignedDelivery(bytes)) {
            is EstablishmentEnrollmentAcceptor.Outcome.Accepted -> {
                if (outcome.idempotent) {
                    log("state=accept outcome=idempotent taskProfileRevision=${outcome.taskProfileRevision}")
                } else {
                    log("state=accept outcome=Accepted taskProfileRevision=${outcome.taskProfileRevision}")
                }
                runtime.establishmentTrustStore.reloadFromPersistence()
                readinessGate?.invalidateLocal()
                refreshLocalPhaseFromVerify(logTransition = true)
                if (phase == EnrollmentCoordinatorPhase.LOCAL_ESTABLISHMENT_READY) {
                    stateStore.save(
                        EstablishmentEnrollmentCoordinatorStateStore.Snapshot(
                            phase = phase,
                            lastTaskProfileRevision = outcome.taskProfileRevision,
                            lastFailureCode = null,
                            lastFailureDetail = null,
                        ),
                    )
                    SubmitResult.Accepted(outcome.idempotent)
                } else {
                    SubmitResult.Failed(
                        phase.name,
                        readinessGate?.localReadiness()?.detail ?: "verify failed after accept",
                    )
                }
            }
            is EstablishmentEnrollmentAcceptor.Outcome.Rejected -> {
                phase = EnrollmentCoordinatorPhase.ENROLLMENT_REJECTED
                persistPhase(outcome.code, outcome.reason)
                log("state=$phase code=${outcome.code} reason=${outcome.reason}")
                SubmitResult.Failed(outcome.code, outcome.reason)
            }
        }
    }

    private fun resumeFromDisk() {
        stateStore.load()?.let { saved ->
            if (saved.phase == EnrollmentCoordinatorPhase.LOCAL_ESTABLISHMENT_READY) {
                phase = saved.phase
            }
        }
        ensureLocalIdentityStep()
        refreshLocalPhaseFromVerify(logTransition = true)
    }

    private fun ensureLocalIdentityStep() {
        if (phase == EnrollmentCoordinatorPhase.LOCAL_ESTABLISHMENT_READY) return
        if (phase == EnrollmentCoordinatorPhase.LOCAL_IDENTITY_FAILED) return
        val ensurer = EstablishmentLocalIdentityEnsurer(keystoreStore)
        when (val outcome = ensurer.ensureLocalIdentity(moduleId)) {
            is EstablishmentLocalIdentityEnsurer.Outcome.Ready -> {
                log(
                    "E1 module=${EstablishmentEnrollmentPolicy.normalizeModuleId(moduleId)} " +
                        "newlyCreated=${outcome.newlyCreated}",
                )
                if (phase == EnrollmentCoordinatorPhase.LOCAL_IDENTITY_PENDING) {
                    phase = EnrollmentCoordinatorPhase.AWAITING_ENROLLMENT
                    persistPhase()
                }
            }
            is EstablishmentLocalIdentityEnsurer.Outcome.Failed -> {
                phase = EnrollmentCoordinatorPhase.LOCAL_IDENTITY_FAILED
                persistPhase(outcome.code, outcome.reason)
                log("E1 failed code=${outcome.code} reason=${outcome.reason}")
            }
        }
    }

    private fun refreshLocalPhaseFromVerify(logTransition: Boolean) {
        val gate = readinessGate ?: return
        val readiness = gate.localReadiness()
        if (readiness.ready) {
            if (phase != EnrollmentCoordinatorPhase.LOCAL_ESTABLISHMENT_READY) {
                phase = EnrollmentCoordinatorPhase.LOCAL_ESTABLISHMENT_READY
                persistPhase()
                if (logTransition) {
                    log("state=LOCAL_ESTABLISHMENT_READY detail=${readiness.detail}")
                }
            }
        } else if (
            phase != EnrollmentCoordinatorPhase.ENROLLMENT_SUBMITTED &&
                phase != EnrollmentCoordinatorPhase.ENROLLMENT_REJECTED &&
                phase != EnrollmentCoordinatorPhase.LOCAL_IDENTITY_FAILED
        ) {
            phase = EnrollmentCoordinatorPhase.AWAITING_ENROLLMENT
            persistPhase(readiness.reasonCode, readiness.detail)
        }
    }

    private fun persistPhase(failureCode: String? = null, failureDetail: String? = null) {
        stateStore.save(
            EstablishmentEnrollmentCoordinatorStateStore.Snapshot(
                phase = phase,
                lastTaskProfileRevision = stateStore.load()?.lastTaskProfileRevision,
                lastFailureCode = failureCode,
                lastFailureDetail = failureDetail,
            ),
        )
    }

    private fun log(line: String) {
        onLog("ENROLLMENT_COORDINATOR $line")
    }

    sealed class SubmitResult {
        data class Accepted(val idempotent: Boolean) : SubmitResult()

        data class Failed(val code: String, val reason: String) : SubmitResult()

        data object Busy : SubmitResult()
    }
}
