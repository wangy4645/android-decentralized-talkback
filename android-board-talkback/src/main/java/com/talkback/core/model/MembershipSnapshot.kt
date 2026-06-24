package com.talkback.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Authority-pushed membership view for RESYNC / snapshot-only GROUP_INVITE.
 * Does not carry media; receivers apply via [com.talkback.core.session.GroupMembershipSupport.applyMembershipSnapshot].
 */
data class MembershipSnapshot(
    val rosterEpoch: Long,
    val anchorEpoch: Long,
    val members: List<String>
) {
    fun encode(): JSONObject {
        val arr = JSONArray()
        members.forEach { arr.put(it) }
        return JSONObject()
            .put("rosterEpoch", rosterEpoch)
            .put("anchorEpoch", anchorEpoch)
            .put("members", arr)
    }

    companion object {
        fun decode(json: JSONObject): MembershipSnapshot? = runCatching {
            val membersArr = json.optJSONArray("members") ?: JSONArray()
            val members = buildList {
                for (i in 0 until membersArr.length()) {
                    add(membersArr.getString(i))
                }
            }
            MembershipSnapshot(
                rosterEpoch = json.getLong("rosterEpoch"),
                anchorEpoch = json.optLong("anchorEpoch", 0L),
                members = members
            )
        }.getOrNull()
    }
}
