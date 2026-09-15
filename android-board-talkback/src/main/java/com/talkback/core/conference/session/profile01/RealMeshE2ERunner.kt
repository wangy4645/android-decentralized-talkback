package com.talkback.core.conference.session.profile01

import android.content.Context
import android.util.Log
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlPlaneConferenceSessionMediaFactPort
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam
import com.talkback.core.conference.session.profile01.wire.Profile01Q5EmbeddedVectors
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.conference.transport.ConferenceMulticastRtpSrtpTransport
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.conference.wire.ConferenceWireConstants
import com.talkback.core.conference.wire.ConferenceWireEgress
import com.talkback.core.conference.wire.WireIngressResult
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Real Mesh E2E — Underlay Migration Validation only.
 *
 * Same FIELD-VALIDATED product chain (Profile01 → Wiring.forProduct).
 * Authority path unchanged; only underlay path is real cross-device multicast TX/RX
 * on an explicitly named Mesh interface (R1–R4).
 *
 * NOT N=10 capacity. NOT Meeting UI. NOT ADR-0056 cutover.
 * NOT ProductChainFieldSoak local-admit substitute.
 */
enum class RealMeshE2ERole {
    SENDER,
    RECEIVER,
}

class RealMeshE2ERunner(
    private val context: Context,
) {
    data class Config(
        val runId: String,
        val role: RealMeshE2ERole,
        val deviceLabel: String,
        val localModuleId: String,
        val durationSec: Int = RealMeshE2EConstants.DEFAULT_SOAK_SEC,
        /** R1 — required; blank → ENV_BLOCKED. */
        val networkInterfaceName: String,
        val doLeaveRejoin: Boolean = true,
        val nominalPps: Int = Slice4MulticastNetworkConstants.NOMINAL_PPS,
    )

    fun run(config: Config): File {
        val startedAtMs = System.currentTimeMillis()
        val report = linkedMapOf<String, Any?>()
        report["runId"] = config.runId
        report["deviceLabel"] = config.deviceLabel
        report["localModuleId"] = config.localModuleId
        report["role"] = config.role.name
        report["evidenceScope"] = RealMeshE2EConstants.EVIDENCE_SCOPE
        report["meshClaim"] = true
        report["notN10Capacity"] = true
        report["notMeetingUiCutover"] = true
        report["notAdr0056Cutover"] = true
        report["networkInterfaceName"] = config.networkInterfaceName
        report["durationSec"] = config.durationSec

        val ifaceProbe = probeInterface(config.networkInterfaceName)
        report["ifaceProbe"] = ifaceProbe
        report["localIpOnIface"] = ifaceProbe["localIpOnIface"]
        if (ifaceProbe["ok"] != true) {
            report["r1Pass"] = false
            report["verdict"] = "ENV_BLOCKED"
            report["envBlockReason"] = ifaceProbe["reason"]
            report["elapsedMs"] = System.currentTimeMillis() - startedAtMs
            return writeReport(config, report)
        }

        val registry = ConferenceSessionMediaControlFactRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val decrypt =
            Profile01MediaKeyPackageDecryptSeam(Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam())
        val validator =
            Profile01ConferenceMediaFactValidator(Profile01DirectedWireFixtures.goldenVectorTrustBoundary())
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge = bridge,
                mediaKeyDecrypt = decrypt,
                supplementRegistry = supplementRegistry,
            )
        val wiring = ConferenceSessionMediaWiring.forProduct(context)
        ConferenceSessionMediaBridge.wiring = wiring
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            ControlPlaneConferenceSessionMediaFactPort(registry)

        try {
            val q5Wire = Profile01Q5EmbeddedVectors.wirePackage()
            val decrypted =
                decrypt.decrypt(
                    q5Wire,
                    Profile01Q5EmbeddedVectors.RECIPIENT_MODULE_ID,
                    Profile01Q5EmbeddedVectors.RECIPIENT_KEY_VERSION,
                )
            require(decrypted is Profile01MediaKeyPackageDecryptResult.Ready) {
                "Q5 decrypt failed: $decrypted"
            }
            val q5Material = (decrypted as Profile01MediaKeyPackageDecryptResult.Ready).material
            supplementRegistry.put(
                q5Material.copy(
                    conferenceId = Profile01DirectedWireFixtures.CONFERENCE_ID,
                    conferenceEpoch = 7L,
                    membershipVersion = 0L,
                    mediaKeyEpoch = 1L,
                ),
            )
            report["decryptPass"] = true

            val creation =
                ingress.ingestCreationSignedFact(
                    Profile01DirectedWireFixtures.creationSignedFactBytes,
                    Profile01SessionMediaSupplement(
                        channelId = RealMeshE2EConstants.CHANNEL_ID,
                        masterKey = q5Material.masterKey.copyOf(),
                        masterSalt = q5Material.masterSalt.copyOf(),
                        keyContextHint64 = q5Material.keyContextHint64.copyOf(),
                    ),
                    config.networkInterfaceName,
                )
            report["creationDecode"] = creation.decode.javaClass.simpleName
            report["creationPublish"] = creation.ingress?.publishOutcome?.name

            val membership =
                ingress.ingestMembershipSignedFact(
                    Profile01DirectedWireFixtures.membershipSignedFactBytes,
                )
            report["membership"] = membership.javaClass.simpleName
            val generation =
                (membership as? Profile01MembershipIngressResult.Converged)?.generation
                    ?: error("membership not converged: $membership")
            report["membershipVersion"] = generation.membershipVersion
            report["mediaKeyEpoch"] = generation.mediaKeyEpoch

            ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
                generation.conferenceId,
                RealMeshE2EConstants.CHANNEL_ID,
                generation.membershipVersion,
            )
            report["sessionStarted"] = wiring.hasSession(generation.conferenceId)

            val transport = wiring.transport(generation.conferenceId)
            val bind = transport?.lastSocketBindResult()
            val bindOk =
                transport != null &&
                    bind != null &&
                    bind.bindSucceeded &&
                    bind.networkInterfaceName == config.networkInterfaceName
            report["bindResult"] =
                mapOf(
                    "bindSucceeded" to (bind?.bindSucceeded ?: false),
                    "networkInterfaceName" to bind?.networkInterfaceName,
                    "error" to bind?.error,
                    "joinSucceeded" to (transport?.lastJoinSucceeded() ?: false),
                    "joinError" to transport?.lastJoinError(),
                    "configureIfaceOk" to
                        (transport?.configureNetworkInterface(config.networkInterfaceName) ?: false),
                )
            report["r1Pass"] = bindOk
            if (!bindOk) {
                report["verdict"] = "FAIL"
                report["failGate"] = "R1"
                ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped(generation.conferenceId)
                if (wiring.hasSession(generation.conferenceId)) {
                    ConferenceSessionMediaBridge.stopSession(generation.conferenceId)
                }
                report["elapsedMs"] = System.currentTimeMillis() - startedAtMs
                return writeReport(config, report)
            }

            val memberWire = Profile01DirectedWireFixtures.decodeMemberSourceWireFact()
            val memberIngress =
                ingress.ingestSourceDeclarationSignedFact(
                    Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes,
                )
            report["sourceDecode"] = memberIngress.decode.javaClass.simpleName
            report["sourcePublish"] = memberIngress.ingress?.publishOutcome?.name

            ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(
                generation.conferenceId,
                memberWire.moduleId,
            )
            val orch = wiring.orchestrator(generation.conferenceId)
            report["sourceInstalled"] =
                orch?.selection?.registry?.isInstalledExecutable(
                    memberWire.moduleId,
                    memberWire.sourceGeneration,
                ) == true

            val stale =
                ingress.ingestMember(
                    memberWire.copy(sourceGeneration = 0L, membershipVersion = 0L),
                )
            report["staleRejected"] = stale.publishOutcome == null
            report["staleReason"] =
                (stale.validation as? Profile01ValidationResult.NotPublished)?.reason
                    ?: (stale.validation as? Profile01ValidationResult.Invalid)?.reason

            val sessionFact = registry.session(generation.conferenceId)!!
            val soak =
                when (config.role) {
                    RealMeshE2ERole.SENDER ->
                        soakSend(
                            transport = transport!!,
                            endpoint = sessionFact.endpoint,
                            masterKey = sessionFact.masterKey,
                            masterSalt = sessionFact.masterSalt,
                            memberWire = memberWire,
                            durationSec = config.durationSec,
                            nominalPps = config.nominalPps,
                        )
                    RealMeshE2ERole.RECEIVER ->
                        soakReceive(
                            wiring = wiring,
                            transport = transport!!,
                            conferenceId = generation.conferenceId,
                            durationSec = config.durationSec,
                        )
                }
            report.putAll(soak)

            val midSnap = wiring.runtimeSnapshot(generation.conferenceId)
            report["midSoakRuntime"] =
                mapOf(
                    "admittedCount" to midSnap?.admittedCount,
                    "activeJitterSources" to midSnap?.activeJitterSources,
                    "liveDecoders" to midSnap?.liveDecoders,
                    "transportScopeActive" to midSnap?.transportScopeActive,
                )

            if (config.doLeaveRejoin) {
                ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberRemoved(
                    generation.conferenceId,
                    memberWire.moduleId,
                )
                report["afterLeaveInstalled"] =
                    orch?.selection?.registry?.isInstalledExecutable(
                        memberWire.moduleId,
                        memberWire.sourceGeneration,
                    ) == true
                val rejoin = memberWire.copy(sourceGeneration = memberWire.sourceGeneration + 1L)
                ingress.ingestMember(rejoin)
                ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(
                    generation.conferenceId,
                    rejoin.moduleId,
                )
                report["rejoinInstalled"] =
                    wiring.orchestrator(generation.conferenceId)
                        ?.selection
                        ?.registry
                        ?.isInstalledExecutable(rejoin.moduleId, rejoin.sourceGeneration) == true
            }

            ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped(generation.conferenceId)
            if (wiring.hasSession(generation.conferenceId)) {
                ConferenceSessionMediaBridge.stopSession(generation.conferenceId)
            }
            report["teardownSessionGone"] = !wiring.hasSession(generation.conferenceId)
            report["teardownLeakPass"] = !wiring.hasSession(generation.conferenceId)

            val r2Pass =
                when (config.role) {
                    RealMeshE2ERole.SENDER -> (report["packetsSent"] as? Number)?.toLong()?.let { it > 0 } == true
                    RealMeshE2ERole.RECEIVER ->
                        (report["wireAdmitAccepted"] as? Number)?.toLong()?.let { it > 0 } == true
                }
            val r3Pass =
                report["sessionStarted"] == true &&
                    report["sourceInstalled"] == true &&
                    report["staleRejected"] == true &&
                    (report["rejoinInstalled"] == true || !config.doLeaveRejoin)
            val r4Pass =
                report["teardownLeakPass"] == true &&
                    (report["soakCompleted"] == true)
            report["r2Pass"] = r2Pass
            report["r3Pass"] = r3Pass
            report["r4Pass"] = r4Pass
            report["verdict"] =
                if (report["r1Pass"] == true && r2Pass && r3Pass && r4Pass) {
                    "PASS"
                } else {
                    "FAIL"
                }
            if (report["verdict"] == "FAIL") {
                report["failGate"] =
                    when {
                        report["r1Pass"] != true -> "R1"
                        !r2Pass -> "R2"
                        !r3Pass -> "R3"
                        else -> "R4"
                    }
            }
        } catch (e: Exception) {
            Log.e(RealMeshE2EConstants.LOG_TAG, "e2e failed", e)
            report["error"] = e.message
            report["verdict"] = "FAIL"
        } finally {
            ConferenceSessionMediaBridge.wiring = null
            ConferenceSessionMediaCoordinatorDelegate.factPort = null
        }

        report["elapsedMs"] = System.currentTimeMillis() - startedAtMs
        return writeReport(config, report)
    }

    private fun probeInterface(name: String): Map<String, Any?> {
        if (name.isBlank()) {
            return mapOf("ok" to false, "reason" to "IFACE_BLANK")
        }
        val ni =
            try {
                NetworkInterface.getByName(name)
            } catch (e: Exception) {
                return mapOf("ok" to false, "reason" to "IFACE_LOOKUP_ERROR", "error" to e.message)
            } ?: return mapOf("ok" to false, "reason" to "IFACE_MISSING", "iface" to name)
        if (!ni.isUp) {
            return mapOf("ok" to false, "reason" to "IFACE_DOWN", "iface" to name)
        }
        val ipv4 =
            ni.inetAddresses
                .asSequence()
                .mapNotNull { it as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress }
                ?: return mapOf("ok" to false, "reason" to "NO_IPV4", "iface" to name)
        return mapOf(
            "ok" to true,
            "iface" to name,
            "localIpOnIface" to ipv4.hostAddress,
            "isUp" to ni.isUp,
            "supportsMulticast" to ni.supportsMulticast(),
        )
    }

    private fun soakSend(
        transport: ConferenceMulticastRtpSrtpTransport,
        endpoint: com.talkback.core.conference.runtime.MediaGroupEndpointBinding,
        masterKey: ByteArray,
        masterSalt: ByteArray,
        memberWire: Profile01WireMemberSourceFact,
        durationSec: Int,
        nominalPps: Int,
    ): Map<String, Any?> {
        val endMs = System.currentTimeMillis() + durationSec * 1000L
        val opus = OpusTestVectors.encodeTone(440.0)
        var packetsSent = 0L
        var sendErrors = 0L
        var nextSeq = 0x5000
        val intervalNs = 1_000_000_000L / nominalPps.coerceAtLeast(1)
        var nextSendNs = System.nanoTime()
        while (System.currentTimeMillis() < endMs) {
            val nowNs = System.nanoTime()
            if (nowNs < nextSendNs) {
                val sleepMs = ((nextSendNs - nowNs) / 1_000_000L).coerceAtMost(5L)
                if (sleepMs > 0) Thread.sleep(sleepMs)
                continue
            }
            val packet =
                buildProtected(
                    ssrc = memberWire.ssrc,
                    mediaSlot = nextSeq++,
                    admissionKey = memberWire.sourceAdmissionKey48,
                    opusPayload = opus,
                    masterKey = masterKey,
                    masterSalt = masterSalt,
                )
            if (transport.sendProtectedArtifact(packet, endpoint)) {
                packetsSent++
            } else {
                sendErrors++
            }
            nextSendNs += intervalNs
        }
        return mapOf(
            "packetsSent" to packetsSent,
            "sendErrors" to sendErrors,
            "soakCompleted" to true,
            "syntheticLocalAdmit" to false,
        )
    }

    private fun soakReceive(
        wiring: ConferenceSessionMediaWiring,
        transport: ConferenceMulticastRtpSrtpTransport,
        conferenceId: String,
        durationSec: Int,
    ): Map<String, Any?> {
        val endMs = System.currentTimeMillis() + durationSec * 1000L
        var datagramsReceived = 0L
        var wireAdmitAccepted = 0L
        var wireAdmitRejected = 0L
        var lastRejectReason: String? = null
        while (System.currentTimeMillis() < endMs) {
            when (val outcome = transport.receiveOnce()) {
                is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw -> {
                    datagramsReceived++
                    when (val admit = wiring.admitDatagram(conferenceId, outcome.payload)) {
                        is WireIngressResult.Accepted -> wireAdmitAccepted++
                        is WireIngressResult.Rejected -> {
                            wireAdmitRejected++
                            lastRejectReason = admit.reason
                        }
                    }
                }
                is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Ingress -> {
                    datagramsReceived++
                    when (outcome.result) {
                        is WireIngressResult.Accepted -> wireAdmitAccepted++
                        is WireIngressResult.Rejected -> {
                            wireAdmitRejected++
                            lastRejectReason = (outcome.result as WireIngressResult.Rejected).reason
                        }
                    }
                }
                null -> Unit
            }
        }
        return mapOf(
            "datagramsReceived" to datagramsReceived,
            "wireAdmitAccepted" to wireAdmitAccepted,
            "wireAdmitRejected" to wireAdmitRejected,
            "lastRejectReason" to lastRejectReason,
            "soakCompleted" to true,
            "syntheticLocalAdmit" to false,
        )
    }

    private fun buildProtected(
        ssrc: Int,
        mediaSlot: Int,
        admissionKey: ByteArray,
        opusPayload: ByteArray,
        masterKey: ByteArray,
        masterSalt: ByteArray,
    ): ByteArray {
        require(opusPayload.size <= ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS)
        val header =
            Phase1MediaHarness.buildHeaderHe(
                ssrc = ssrc,
                seq = mediaSlot,
                sourceAdmissionKey48 = admissionKey,
                voiceActiveAudioLevel = 0x80 or 10,
            )
        return when (
            val result =
                ConferenceWireEgress.protect(
                    headerAndHe = header,
                    plaintextPayload = opusPayload,
                    masterKey = masterKey,
                    masterSalt = masterSalt,
                    ssrc = ssrc,
                    roc = 0,
                    seq = mediaSlot,
                )
        ) {
            is ConferenceWireEgress.EgressResult.Protected -> result.udpPayload.copyOf()
            is ConferenceWireEgress.EgressResult.Rejected ->
                error("protect failed: ${result.frozenClass} ${result.reason}")
        }
    }

    private fun writeReport(config: Config, fields: Map<String, Any?>): File {
        val dir = File(context.filesDir, "real-mesh-e2e-${config.runId}").apply { mkdirs() }
        val file = File(dir, "${config.deviceLabel}-${config.role.name.lowercase()}-report.json")
        val json = JSONObject()
        for ((k, v) in fields) {
            json.put(k, toJson(v))
        }
        file.writeText(json.toString(2))
        Log.i(RealMeshE2EConstants.LOG_TAG, "REPORT ${file.absolutePath}")
        return file
    }

    private fun toJson(value: Any?): Any? =
        when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val o = JSONObject()
                for ((k, v) in value) {
                    o.put(k.toString(), toJson(v))
                }
                o
            }
            else -> value
        }
}
