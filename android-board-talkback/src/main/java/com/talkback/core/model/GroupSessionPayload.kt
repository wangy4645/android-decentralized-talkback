package com.talkback.core.model

import com.talkback.core.session.GroupMediaTopology
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON payload for GROUP_INVITE / GROUP_JOIN (mesh completion).
 */
data class GroupSessionPayload(
    val sdp: String,
    val channelId: String,
    val members: List<String>,
    val initiatorModuleId: String,
    val floorAuthorityModuleId: String,
    val sessionMode: MeshSessionMode = MeshSessionMode.GROUP,
    val mediaTopology: String? = null,
    val anchorModuleId: String? = null,
    val backupAnchorModuleId: String? = null,
    val anchorEpoch: Long = 0L,
    val rosterEpochMs: Long = 0L,
    val rosterEpoch: Long = 0L,
    /** FNV-1a digest of [channelId, rosterEpoch, sorted canonical module ids]. */
    val memberHash: Int = 0,
    /** True when host pulls a prior member back (rejoin), not a first-time invite. */
    val rejoin: Boolean = false,
    /** ADR-0021 D1: distinguishes mesh join from recovery reattach on GROUP_JOIN. */
    val joinIntent: ConferenceJoinIntent = ConferenceJoinIntent.NORMAL_JOIN,
    /** Control-plane roster sync without media (RESYNC response). */
    val membershipSnapshot: MembershipSnapshot? = null,
    /**
     * Per-offer correlation id for ICE-restart / recovery GROUP_JOIN (observation).
     * Optional; older peers ignore unknown keys. Used to join OFFER_SENT ↔ OFFER_RECEIVED.
     */
    val offerLineageId: String? = null,
    /** Sender-side recovery attempt id for the offer (observation / correlation). */
    val restartAttemptId: Long? = null,
    /** Sender-side transport/PC generation stamped on the offer (observation / correlation). */
    val transportGeneration: Long? = null,
    /** ADR-0035: obligation generation stamped on recovery offer (ACK correlation). */
    val obligationGeneration: Long? = null,
    /** ADR-0035 PR1: delivery attempt within a lineage (default 1; no retry owner yet). */
    val deliveryAttemptId: Long = 1L,
    /** ADR-0037 Phase 3.2: wire-carried negotiation owner (A), not a second election. */
    val negotiationOwnerModuleId: String? = null
) {
    fun encode(): String {
        val arr = JSONArray()
        members.forEach { arr.put(it) }
        val json = JSONObject()
            .put("sdp", sdp)
            .put("channelId", channelId)
            .put("members", arr)
            .put("initiatorModuleId", initiatorModuleId)
            .put("floorAuthorityModuleId", floorAuthorityModuleId)
            .put("sessionMode", sessionMode.encode())
        if (!mediaTopology.isNullOrBlank()) {
            json.put("mediaTopology", mediaTopology)
        }
        if (!anchorModuleId.isNullOrBlank()) {
            json.put("anchorModuleId", anchorModuleId)
        }
        if (!backupAnchorModuleId.isNullOrBlank()) {
            json.put("backupAnchorModuleId", backupAnchorModuleId)
        }
        if (anchorEpoch > 0L) {
            json.put("anchorEpoch", anchorEpoch)
        }
        if (rosterEpochMs > 0L) {
            json.put("rosterEpochMs", rosterEpochMs)
        }
        if (rosterEpoch > 0L) {
            json.put("rosterEpoch", rosterEpoch)
        }
        if (memberHash != 0) {
            json.put("memberHash", memberHash)
        }
        if (rejoin) {
            json.put("rejoin", true)
        }
        if (joinIntent != ConferenceJoinIntent.NORMAL_JOIN) {
            json.put("joinIntent", joinIntent.encode())
        }
        membershipSnapshot?.let { json.put("membershipSnapshot", it.encode()) }
        if (!offerLineageId.isNullOrBlank()) {
            json.put("offerLineageId", offerLineageId)
        }
        if (restartAttemptId != null && restartAttemptId > 0L) {
            json.put("restartAttemptId", restartAttemptId)
        }
        if (transportGeneration != null && transportGeneration > 0L) {
            json.put("transportGeneration", transportGeneration)
        }
        if (obligationGeneration != null && obligationGeneration > 0L) {
            json.put("obligationGeneration", obligationGeneration)
        }
        if (deliveryAttemptId > 0L) {
            json.put("deliveryAttemptId", deliveryAttemptId)
        }
        if (!negotiationOwnerModuleId.isNullOrBlank()) {
            json.put("negotiationOwnerModuleId", negotiationOwnerModuleId)
        }
        return json.toString()
    }

    companion object {
        fun decode(raw: String): GroupSessionPayload? {
            return runCatching {
                val json = JSONObject(raw)
                val membersArr = json.optJSONArray("members") ?: JSONArray()
                val members = buildList {
                    for (i in 0 until membersArr.length()) {
                        add(membersArr.getString(i))
                    }
                }
                GroupSessionPayload(
                    sdp = json.getString("sdp"),
                    channelId = json.optString("channelId", "CH-01"),
                    members = members,
                    initiatorModuleId = json.optString("initiatorModuleId", ""),
                    floorAuthorityModuleId = json.optString("floorAuthorityModuleId", ""),
                    sessionMode = MeshSessionMode.fromPayload(
                        json.optString("sessionMode").takeIf { it.isNotBlank() }
                    ),
                    mediaTopology = json.optString("mediaTopology").takeIf { it.isNotBlank() },
                    anchorModuleId = json.optString("anchorModuleId").takeIf { it.isNotBlank() },
                    backupAnchorModuleId = json.optString("backupAnchorModuleId").takeIf { it.isNotBlank() },
                    anchorEpoch = json.optLong("anchorEpoch", 0L),
                    rosterEpochMs = json.optLong("rosterEpochMs", 0L),
                    rosterEpoch = json.optLong("rosterEpoch", 0L),
                    memberHash = json.optInt("memberHash", 0),
                    rejoin = json.optBoolean("rejoin", false),
                    joinIntent = ConferenceJoinIntent.fromPayload(
                        json.optString("joinIntent").takeIf { it.isNotBlank() }
                    ),
                    membershipSnapshot = json.optJSONObject("membershipSnapshot")?.let {
                        MembershipSnapshot.decode(it)
                    },
                    offerLineageId = json.optString("offerLineageId").takeIf { it.isNotBlank() },
                    restartAttemptId = json.optLong("restartAttemptId", 0L).takeIf { it > 0L },
                    transportGeneration = json.optLong("transportGeneration", 0L).takeIf { it > 0L },
                    obligationGeneration = json.optLong("obligationGeneration", 0L).takeIf { it > 0L },
                    deliveryAttemptId = json.optLong("deliveryAttemptId", 1L).coerceAtLeast(1L),
                    negotiationOwnerModuleId = json.optString("negotiationOwnerModuleId").takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        }

        fun parseMembers(keys: List<String>): List<EndpointAddress> {
            return keys.mapNotNull { key ->
                runCatching {
                    val dash = key.indexOf('-')
                    if (dash <= 0) return@runCatching null
                    EndpointAddress(
                        ModuleId(key.substring(0, dash)),
                        EndpointId(key.substring(dash + 1))
                    )
                }.getOrNull()
            }
        }
    }
}
