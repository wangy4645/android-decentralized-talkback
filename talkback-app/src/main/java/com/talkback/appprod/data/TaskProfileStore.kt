package com.talkback.appprod.data

import android.content.Context
import android.content.SharedPreferences

class TaskProfileStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun listProfiles(): List<TaskProfile> =
        TaskProfile.decodeList(prefs.getString(KEY_PROFILES_JSON, "") ?: "")

    fun getProfile(id: String): TaskProfile? =
        listProfiles().firstOrNull { it.id == id }

    fun activeProfileId(): String = prefs.getString(KEY_ACTIVE_ID, "") ?: ""

    fun activeProfile(): TaskProfile? {
        val id = activeProfileId()
        if (id.isBlank()) return null
        return getProfile(id)
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun saveProfiles(profiles: List<TaskProfile>) {
        prefs.edit()
            .putString(KEY_PROFILES_JSON, TaskProfile.encodeList(profiles))
            .apply()
    }

    fun saveProfile(profile: TaskProfile) {
        val updated = listProfiles().toMutableList()
        val index = updated.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            updated[index] = profile
        } else {
            updated.add(profile)
        }
        saveProfiles(updated)
    }

    fun deleteProfile(id: String): Boolean {
        val updated = listProfiles().filterNot { it.id == id }
        if (updated.size == listProfiles().size) return false
        saveProfiles(updated)
        if (activeProfileId() == id) {
            setActiveProfileId(updated.firstOrNull()?.id ?: "")
        }
        return true
    }

    fun migrateFromAppConfigIfNeeded(appConfigStore: AppConfigStore) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        if (listProfiles().isNotEmpty()) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }
        val config = appConfigStore.load()
        val profile = TaskProfile.createNew(
            name = config.channelDisplayName.ifBlank { "Task 1" },
            sharedSecret = config.sharedSecret,
            channelId = config.defaultChannelId,
            channelDisplayName = config.channelDisplayName,
            staticPeersJson = config.staticPeersJson
        )
        saveProfiles(listOf(profile))
        setActiveProfileId(profile.id)
        appConfigStore.save(config.copy(activeTaskProfileId = profile.id))
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "talkback_task_profiles"
        private const val KEY_PROFILES_JSON = "profilesJson"
        private const val KEY_ACTIVE_ID = "activeProfileId"
        private const val KEY_MIGRATED = "migrated"
    }
}
