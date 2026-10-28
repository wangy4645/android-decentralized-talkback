package com.talkback.appprod.fieldtest

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.talkback.core.session.gbc.trust.profile.establishment.Ep1RemoteLookupOutcome
import com.talkback.core.session.gbc.trust.profile.establishment.Ep2LocalPossessionOutcome
import com.talkback.core.session.gbc.trust.profile.establishment.Ep4IdentityDomainOutcome
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentFieldProbe
import com.talkback.core.session.gbc.wiring.EstablishmentFieldProbeRuntime
import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition
import java.io.File

/**
 * EP1–EP5 establishment-only field probe runner (read-only).
 *
 * Emits `EP_PROBE` lines to logcat tag [LOG_TAG]. No trust mutation.
 */
object EpEstablishmentFieldGateSupport {
    const val LOG_TAG = "Talkback"
    private const val EP_PROBE_PREFIX = "EP_PROBE"

    data class RunResult(
        val localModuleId: String,
        val peerModuleId: String,
        val establishmentKeyVersion: Long,
        val ep1: Ep1RemoteLookupOutcome?,
        val ep2: Ep2LocalPossessionOutcome,
        val ep3: Ep1RemoteLookupOutcome?,
        val ep4: Ep4IdentityDomainOutcome,
    )

    fun run(context: Context): RunResult {
        val localModuleId = FieldTestProvisioningSupport.readConfiguredModuleId(context)
        val peerModuleId = resolvePeerModuleId(localModuleId)
        val establishmentKeyVersion = resolveEstablishmentKeyVersion()
        val trustDir = File(context.filesDir, "gbc-trust")
        val session =
            EstablishmentFieldProbeRuntime.fromProductionTrustDir(
                deploymentTrustDomainId = GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN,
                trustDir = trustDir.toPath(),
                localModuleId = localModuleId,
            )
        val log: (String) -> Unit = { line -> Log.i(LOG_TAG, line) }
        log("$EP_PROBE_PREFIX RUN_BEGIN device=$localModuleId peer=$peerModuleId version=$establishmentKeyVersion")
        val ep1 =
            if (localModuleId == "M01") {
                EstablishmentFieldProbe.logEp1RemoteLookup(
                    session = session,
                    peerModuleId = peerModuleId,
                    establishmentKeyVersion = establishmentKeyVersion,
                    log = log,
                )
            } else {
                log("$EP_PROBE_PREFIX case=EP1 device=$localModuleId outcome=N/A reason=primary_direction_host_is_M01")
                null
            }
        val ep2 = EstablishmentFieldProbe.logEp2LocalPossession(session = session, log = log)
        val ep3 =
            if (requiresReverseDirection()) {
                EstablishmentFieldProbe.logEp1RemoteLookup(
                    session = session,
                    peerModuleId = localModuleId,
                    establishmentKeyVersion = establishmentKeyVersion,
                    log = { line -> Log.i(LOG_TAG, line.replace("case=EP1", "case=EP3")) },
                )
            } else {
                log("$EP_PROBE_PREFIX case=EP3 device=$localModuleId outcome=N/A reason=reverse_not_required")
                null
            }
        val ep4 = EstablishmentFieldProbe.logEp4IdentityDomainSeparation(session = session, log = log)
        EstablishmentFieldProbe.logEp5NegativeFence(
            session = session,
            peerModuleId = peerModuleId,
            wrongEstablishmentKeyVersion = establishmentKeyVersion + 99L,
            log = log,
        )
        log("$EP_PROBE_PREFIX RUN_END device=$localModuleId")
        return RunResult(
            localModuleId = localModuleId,
            peerModuleId = peerModuleId,
            establishmentKeyVersion = establishmentKeyVersion,
            ep1 = ep1,
            ep2 = ep2,
            ep3 = ep3,
            ep4 = ep4,
        )
    }

    private fun resolvePeerModuleId(localModuleId: String): String {
        val args = InstrumentationRegistry.getArguments()
        return args.getString("peerModuleId")?.trim()?.uppercase()
            ?: when (localModuleId) {
                "M01" -> "M02"
                "M02" -> "M01"
                else -> error("peerModuleId instrumentation arg required for $localModuleId")
            }
    }

    private fun resolveEstablishmentKeyVersion(): Long {
        val args = InstrumentationRegistry.getArguments()
        return args.getString("establishmentKeyVersion")?.trim()?.toLongOrNull()
            ?: error("establishmentKeyVersion instrumentation arg required (provisioning record)")
    }

    private fun requiresReverseDirection(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString("ep3Mode")
            ?.equals("required", ignoreCase = true) == true
}
