package com.talkback.core.webrtc

import com.talkback.core.util.TalkbackLog
import org.webrtc.PeerConnection
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.security.MessageDigest

/**
 * Behavior-neutral WebRTC transport diagnostics (ADR-0057 transport RCA).
 * Grep: ICE_TRANSPORT_CANDIDATE | ICE_TRANSPORT_CREDENTIAL | ICE_TRANSPORT_STATS
 */
object IceTransportDiagnostic {

    enum class CandidateSeam {
        GENERATED,
        SEND,
        RECEIVE,
        APPLY,
    }

    data class ParsedCandidate(
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidateType: String,
        val protocol: String,
        val address: String,
        val port: Int,
        val foundation: String,
        val component: String,
        val priority: String,
        val relatedAddress: String?,
        val relatedPort: String?,
        val generation: String?,
        val rawSdp: String,
    )

    data class IceCredentialIdentity(
        val iceUfrag: String?,
        val icePwdFingerprint: String?,
    )

    fun parseCandidateWire(wire: String, sdpMid: String? = null, sdpMLineIndex: Int = -1): ParsedCandidate {
        val parts = wire.split("|", limit = 3)
        val candidateSdp =
            when {
                parts.size == 3 -> parts[2]
                parts.size == 1 -> parts[0]
                else -> wire
            }
        val mid = sdpMid ?: parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val lineIndex =
            sdpMLineIndex.takeIf { it >= 0 }
                ?: parts.getOrNull(1)?.toIntOrNull()
                ?: -1
        return parseCandidateSdp(candidateSdp, mid, lineIndex)
    }

    fun parseCandidateSdp(
        candidateSdp: String,
        sdpMid: String? = null,
        sdpMLineIndex: Int = -1,
    ): ParsedCandidate {
        val body = candidateSdp.removePrefix("candidate:").trim()
        val tokens = body.split(Regex("\\s+"))
        val typIdx = tokens.indexOf("typ")
        val candidateType = if (typIdx >= 0 && typIdx + 1 < tokens.size) tokens[typIdx + 1] else "unknown"
        val raddrIdx = tokens.indexOf("raddr")
        val rportIdx = tokens.indexOf("rport")
        val genIdx = tokens.indexOf("generation")
        return ParsedCandidate(
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            foundation = tokens.getOrNull(0) ?: "unknown",
            component = tokens.getOrNull(1) ?: "unknown",
            protocol = tokens.getOrNull(2) ?: "unknown",
            priority = tokens.getOrNull(3) ?: "unknown",
            address = tokens.getOrNull(4) ?: "unknown",
            port = tokens.getOrNull(5)?.toIntOrNull() ?: -1,
            candidateType = candidateType,
            relatedAddress = if (raddrIdx >= 0 && raddrIdx + 1 < tokens.size) tokens[raddrIdx + 1] else null,
            relatedPort = if (rportIdx >= 0 && rportIdx + 1 < tokens.size) tokens[rportIdx + 1] else null,
            generation = if (genIdx >= 0 && genIdx + 1 < tokens.size) tokens[genIdx + 1] else null,
            rawSdp = candidateSdp,
        )
    }

    fun extractIceCredentials(sdp: String?): IceCredentialIdentity {
        if (sdp.isNullOrBlank()) {
            return IceCredentialIdentity(iceUfrag = null, icePwdFingerprint = null)
        }
        val ufrag = Regex("""a=ice-ufrag:(\S+)""", RegexOption.MULTILINE).find(sdp)?.groupValues?.get(1)
        val pwd = Regex("""a=ice-pwd:(\S+)""", RegexOption.MULTILINE).find(sdp)?.groupValues?.get(1)
        return IceCredentialIdentity(
            iceUfrag = ufrag,
            icePwdFingerprint = pwd?.let { sha256Prefix(it) },
        )
    }

    fun logCandidate(
        seam: CandidateSeam,
        peer: String,
        offerLineageId: String?,
        pcGeneration: Long?,
        pcHash: Int?,
        wire: String,
        queued: Boolean = false,
        localUfrag: String? = null,
        remoteUfrag: String? = null,
    ) {
        val parsed = parseCandidateWire(wire)
        val lineage = offerLineageId ?: "NONE"
        val gen = pcGeneration?.toString() ?: "NONE"
        val hash = pcHash?.toString() ?: "NONE"
        val queue = if (queued) " queued=true" else ""
        val ufragFields =
            buildString {
                localUfrag?.let { append(" localUfrag=$it") }
                remoteUfrag?.let { append(" remoteUfrag=$it") }
            }
        TalkbackLog.i(
            "ICE_TRANSPORT_CANDIDATE seam=$seam peer=$peer offerLineageId=$lineage " +
                "pcGeneration=$gen pcHash=$hash$queue " +
                "sdpMid=${parsed.sdpMid ?: "NONE"} sdpMLineIndex=${parsed.sdpMLineIndex} " +
                "type=${parsed.candidateType} protocol=${parsed.protocol} " +
                "address=${parsed.address} port=${parsed.port} " +
                "foundation=${parsed.foundation} component=${parsed.component} " +
                "priority=${parsed.priority} " +
                "relatedAddress=${parsed.relatedAddress ?: "NONE"} " +
                "relatedPort=${parsed.relatedPort ?: "NONE"} " +
                "generation=${parsed.generation ?: "NONE"}$ufragFields"
        )
    }

    fun logCredentialBoundary(
        op: String,
        descriptionType: String,
        peer: String,
        offerLineageId: String?,
        pcGeneration: Long?,
        pcHash: Int?,
        sdp: String?,
    ) {
        val creds = extractIceCredentials(sdp)
        val lineage = offerLineageId ?: "NONE"
        val gen = pcGeneration?.toString() ?: "NONE"
        val hash = pcHash?.toString() ?: "NONE"
        TalkbackLog.i(
            "ICE_TRANSPORT_CREDENTIAL op=$op descriptionType=$descriptionType peer=$peer " +
                "offerLineageId=$lineage pcGeneration=$gen pcHash=$hash " +
                "iceUfrag=${creds.iceUfrag ?: "NONE"} " +
                "icePwdFingerprint=${creds.icePwdFingerprint ?: "NONE"}"
        )
    }

    private val statsBoundaryStates =
        setOf("CHECKING", "CONNECTED", "COMPLETED", "FAILED")

    private val lastStatsBoundaryByTag = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun captureStatsAtIceBoundary(
        peerConnection: PeerConnection,
        diagnosticTag: String?,
        iceState: String,
    ) {
        if (iceState !in statsBoundaryStates) return
        if (NativeSignalingOverlapObserver.shouldDeferGetStats()) return
        val tag = diagnosticTag ?: return
        val last = lastStatsBoundaryByTag.put(tag, iceState)
        if (last == iceState) return
        peerConnection.getStats { report ->
            logStatsReport(tag, iceState, report)
        }
    }

    private fun logStatsReport(tag: String, boundary: String, report: RTCStatsReport) {
        val parts = tag.split("|", limit = 2)
        val sessionId = parts.getOrNull(0) ?: tag
        val peer = parts.getOrNull(1) ?: "unknown"
        val candidatesById = mutableMapOf<String, RTCStats>()
        report.statsMap.forEach { (id, stat) ->
            if (stat.type == "local-candidate" || stat.type == "remote-candidate") {
                candidatesById[id] = stat
            }
        }
        val pairs =
            report.statsMap.values.filter { it.type == "candidate-pair" }
        if (pairs.isEmpty()) {
            TalkbackLog.i(
                "ICE_TRANSPORT_STATS boundary=$boundary session=$sessionId peer=$peer " +
                    "candidatePairCount=0"
            )
            return
        }
        pairs.forEach { pair ->
            val localId = pair.members["localCandidateId"]?.toString()
            val remoteId = pair.members["remoteCandidateId"]?.toString()
            val local = localId?.let { candidatesById[it] }
            val remote = remoteId?.let { candidatesById[it] }
            TalkbackLog.i(
                "ICE_TRANSPORT_STATS boundary=$boundary session=$sessionId peer=$peer " +
                    "pairState=${pair.members["state"]} nominated=${pair.members["nominated"]} " +
                    "selected=${pair.members["selected"]} " +
                    "bytesSent=${pair.members["bytesSent"]} bytesReceived=${pair.members["bytesReceived"]} " +
                    "requestsSent=${pair.members["requestsSent"]} responsesReceived=${pair.members["responsesReceived"]} " +
                    "requestsReceived=${pair.members["requestsReceived"]} responsesSent=${pair.members["responsesSent"]} " +
                    "currentRoundTripTime=${pair.members["currentRoundTripTime"]} " +
                    "localCandidateId=$localId remoteCandidateId=$remoteId " +
                    formatCandidateRef("local", local) +
                    formatCandidateRef("remote", remote)
            )
        }
    }

    private fun formatCandidateRef(prefix: String, stat: RTCStats?): String {
        if (stat == null) return " ${prefix}Type=NONE ${prefix}Protocol=NONE ${prefix}Address=NONE ${prefix}Port=NONE"
        return " ${prefix}Type=${stat.members["candidateType"]} " +
            "${prefix}Protocol=${stat.members["protocol"]} " +
            "${prefix}Address=${stat.members["address"]} " +
            "${prefix}Port=${stat.members["port"]} " +
            "${prefix}NetworkType=${stat.members["networkType"] ?: stat.members["adapterType"] ?: "NONE"}"
    }

    private fun sha256Prefix(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
