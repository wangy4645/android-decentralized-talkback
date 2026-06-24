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
    /** Control-plane roster sync without media (RESYNC response). */
    val membershipSnapshot: MembershipSnapshot? = null
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
        membershipSnapshot?.let { json.put("membershipSnapshot", it.encode()) }
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
                    membershipSnapshot = json.optJSONObject("membershipSnapshot")?.let {
                        MembershipSnapshot.decode(it)
                    }
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
