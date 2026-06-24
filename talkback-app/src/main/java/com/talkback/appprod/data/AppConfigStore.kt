package com.talkback.appprod.data

import android.content.Context
import android.content.SharedPreferences
import com.talkback.core.discovery.StaticPeerConfig
import com.talkback.core.discovery.StaticPeerEntry
import com.talkback.core.model.EndpointPriority

data class AppConfig(
    val moduleId: String,
    val endpointId: String,
    val signalingPort: Int,
    val defaultChannelId: String,
    val channelDisplayName: String,
    val autoRedial: Boolean,
    val autoAcceptIncoming: Boolean,
    val autoStartOnBoot: Boolean,
    val sharedSecret: String,
    val whitelistEnabled: Boolean,
    val allowedModuleIds: Set<String>,
    val staticPeersJson: String,
    val channelMode: ChannelMode = ChannelMode.GROUP_PTT,
    val meetingAutoGain: Boolean = true,
    val meetingNoiseSuppression: Boolean = true,
    val meetingLocked: Boolean = false,
    val meetingAutoJoin: Boolean = false,
    val activeTaskProfileId: String = "",
    val localPriority: EndpointPriority = EndpointPriority.NORMAL
) {
    fun isConferenceMode(): Boolean = channelMode == ChannelMode.CONFERENCE
    fun effectiveAllowedModuleIds(): Set<String> =
        if (whitelistEnabled) allowedModuleIds else emptySet()
    fun staticPeers(): List<StaticPeerEntry> = StaticPeerConfig.parse(staticPeersJson)

    fun channelTitle(): String = defaultChannelId.replace("CH-", "Channel ", ignoreCase = true)
        .replace("_", " ")
        .let { if (it.startsWith("Channel ", ignoreCase = true)) it else "Channel $it" }
}

class AppConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("talkback_prod_config", Context.MODE_PRIVATE)

    fun load(): AppConfig {
        val allowed = prefs.getString("allowedModuleIds", "") ?: ""
        val allowedSet = allowed.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        return AppConfig(
            moduleId = prefs.getString("moduleId", "M01") ?: "M01",
            endpointId = prefs.getString("endpointId", "E01") ?: "E01",
            signalingPort = prefs.getInt("signalingPort", 50000),
            defaultChannelId = prefs.getString("defaultChannelId", "CH-01") ?: "CH-01",
            channelDisplayName = prefs.getString("channelDisplayName", "Maintenance Team") ?: "Maintenance Team",
            autoRedial = prefs.getBoolean("autoRedial", true),
            autoAcceptIncoming = prefs.getBoolean("autoAcceptIncoming", true),
            autoStartOnBoot = prefs.getBoolean("autoStartOnBoot", true),
            sharedSecret = prefs.getString("sharedSecret", "") ?: "",
            whitelistEnabled = prefs.getBoolean("whitelistEnabled", false),
            allowedModuleIds = allowedSet,
            staticPeersJson = prefs.getString("staticPeersJson", "") ?: "",
            channelMode = ChannelMode.fromPersisted(prefs.getString("channelMode", null)),
            meetingAutoGain = prefs.getBoolean("meetingAutoGain", true),
            meetingNoiseSuppression = prefs.getBoolean("meetingNoiseSuppression", true),
            meetingLocked = prefs.getBoolean("meetingLocked", false),
            meetingAutoJoin = prefs.getBoolean("meetingAutoJoin", false),
            activeTaskProfileId = prefs.getString("activeTaskProfileId", "") ?: "",
            localPriority = parsePriority(prefs.getString("localPriority", "NORMAL") ?: "NORMAL")
        )
    }

    private fun parsePriority(raw: String): EndpointPriority =
        runCatching { EndpointPriority.valueOf(raw.trim().uppercase()) }
            .getOrDefault(EndpointPriority.NORMAL)

    fun save(config: AppConfig) {
        prefs.edit()
            .putString("moduleId", config.moduleId)
            .putString("endpointId", config.endpointId)
            .putInt("signalingPort", config.signalingPort)
            .putString("defaultChannelId", config.defaultChannelId)
            .putString("channelDisplayName", config.channelDisplayName)
            .putBoolean("autoRedial", config.autoRedial)
            .putBoolean("autoAcceptIncoming", config.autoAcceptIncoming)
            .putBoolean("autoStartOnBoot", config.autoStartOnBoot)
            .putString("sharedSecret", config.sharedSecret)
            .putBoolean("whitelistEnabled", config.whitelistEnabled)
            .putString("allowedModuleIds", config.allowedModuleIds.joinToString(","))
            .putString("staticPeersJson", config.staticPeersJson)
            .putString("channelMode", config.channelMode.persistKey())
            .putBoolean("meetingAutoGain", config.meetingAutoGain)
            .putBoolean("meetingNoiseSuppression", config.meetingNoiseSuppression)
            .putBoolean("meetingLocked", config.meetingLocked)
            .putBoolean("meetingAutoJoin", config.meetingAutoJoin)
            .putString("activeTaskProfileId", config.activeTaskProfileId)
            .putString("localPriority", config.localPriority.name)
            .apply()
    }
}
