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
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.conference.wire.ConferenceWireConstants
import com.talkback.core.conference.wire.ConferenceWireEgress
import com.talkback.core.conference.wire.WireIngressResult
import org.json.JSONObject
import java.io.File

/**
 * Directed PRODUCT-CHAIN field soak — composes CLOSED seams only.
 *
 * NOT Real Mesh E2E. NOT Phase1MediaHarness.installAuthority as authority path.
 */
class ProductChainFieldSoakRunner(
    private val context: Context,
) {
    data class Config(
        val runId: String,
        val deviceLabel: String,
        val localModuleId: String,
        val durationSec: Int = ProductChainFieldSoakConstants.DEFAULT_SOAK_SEC,
        val networkInterfaceName: String = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
        val doLeaveRejoin: Boolean = true,
    )

    fun run(config: Config): File {
        val startedAtMs = System.currentTimeMillis()
        val report = linkedMapOf<String, Any?>()
        report["runId"] = config.runId
        report["deviceLabel"] = config.deviceLabel
        report["localModuleId"] = config.localModuleId
        report["evidenceScope"] = "PRODUCT-CHAIN_FIELD_SOAK"
        report["meshClaim"] = false

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
            report["srtpMasterKeyHex"] = q5Material.masterKey.toHex()

            val creation =
                ingress.ingestCreationSignedFact(
                    Profile01DirectedWireFixtures.creationSignedFactBytes,
                    Profile01SessionMediaSupplement(
                        channelId = ProductChainFieldSoakConstants.CHANNEL_ID,
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
            report["generationFactDigestHex"] = generation.generationFactDigest.toHex()

            ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
                generation.conferenceId,
                ProductChainFieldSoakConstants.CHANNEL_ID,
                generation.membershipVersion,
            )
            report["sessionStarted"] = wiring.hasSession(generation.conferenceId)

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
            val admitOk =
                soakAdmitLoop(
                    wiring = wiring,
                    conferenceId = generation.conferenceId,
                    masterKey = sessionFact.masterKey,
                    masterSalt = sessionFact.masterSalt,
                    memberWire = memberWire,
                    durationSec = config.durationSec,
                )
            report["soakAdmitAccepted"] = admitOk.first
            report["soakAdmitRejected"] = admitOk.second
            report["soakSec"] = config.durationSec

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
            report["verdict"] =
                if (
                    report["sessionStarted"] == true &&
                    report["sourceInstalled"] == true &&
                    report["staleRejected"] == true &&
                    admitOk.first > 0 &&
                    report["teardownLeakPass"] == true
                ) {
                    "PASS"
                } else {
                    "FAIL"
                }
        } catch (e: Exception) {
            Log.e(ProductChainFieldSoakConstants.LOG_TAG, "soak failed", e)
            report["error"] = e.message
            report["verdict"] = "FAIL"
        } finally {
            ConferenceSessionMediaBridge.wiring = null
            ConferenceSessionMediaCoordinatorDelegate.factPort = null
        }

        report["elapsedMs"] = System.currentTimeMillis() - startedAtMs
        return writeReport(config, report)
    }

    private fun soakAdmitLoop(
        wiring: ConferenceSessionMediaWiring,
        conferenceId: String,
        masterKey: ByteArray,
        masterSalt: ByteArray,
        memberWire: Profile01WireMemberSourceFact,
        durationSec: Int,
    ): Pair<Int, Int> {
        var accepted = 0
        var rejected = 0
        val endMs = System.currentTimeMillis() + durationSec * 1000L
        var slot = 100
        val opus = OpusTestVectors.encodeTone(440.0)
        while (System.currentTimeMillis() < endMs) {
            val packet =
                buildProtected(
                    ssrc = memberWire.ssrc,
                    mediaSlot = slot++,
                    admissionKey = memberWire.sourceAdmissionKey48,
                    opusPayload = opus,
                    masterKey = masterKey,
                    masterSalt = masterSalt,
                )
            when (wiring.admitDatagram(conferenceId, packet)) {
                is WireIngressResult.Accepted -> accepted++
                is WireIngressResult.Rejected -> rejected++
            }
            Thread.sleep(20L)
        }
        return accepted to rejected
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
        val dir = File(context.filesDir, "product-chain-field-soak-${config.runId}").apply { mkdirs() }
        val file = File(dir, "${config.deviceLabel}-report.json")
        val json = JSONObject()
        for ((k, v) in fields) {
            json.put(k, v ?: JSONObject.NULL)
        }
        file.writeText(json.toString(2))
        Log.i(ProductChainFieldSoakConstants.LOG_TAG, "REPORT ${file.absolutePath}")
        return file
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
