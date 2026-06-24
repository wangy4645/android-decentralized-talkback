package com.talkback.appprod.data

import android.content.Context
import com.talkback.appprod.TalkbackApp
import com.talkback.appprod.ui.SettingsActions

class TaskProfileManager(context: Context) {
    private val appContext = context.applicationContext
    private val appConfigStore = AppConfigStore(appContext)
    private val profileStore = TaskProfileStore(appContext)

    fun ensureInitialized() {
        profileStore.migrateFromAppConfigIfNeeded(appConfigStore)
        val config = appConfigStore.load()
        val activeId = config.activeTaskProfileId.ifBlank { profileStore.activeProfileId() }
        if (activeId.isNotBlank() && profileStore.getProfile(activeId) != null) {
            if (config.activeTaskProfileId != activeId) {
                appConfigStore.save(config.copy(activeTaskProfileId = activeId))
            }
            return
        }
        profileStore.activeProfile()?.let { applyProfileFields(it) }
    }

    fun listProfiles(): List<TaskProfile> = profileStore.listProfiles()

    fun getProfile(id: String): TaskProfile? = profileStore.getProfile(id)

    fun activeProfile(): TaskProfile? = profileStore.activeProfile()

    fun applyProfile(profileId: String, restartService: Boolean): Result<TaskProfile> {
        val profile = profileStore.getProfile(profileId)
            ?: return Result.failure(IllegalArgumentException("profile not found"))
        SettingsActions.validateSecret(profile.sharedSecret)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        applyProfileFields(profile)
        profileStore.setActiveProfileId(profile.id)
        if (TalkbackApp.get(appContext).serviceRunning) {
            TalkbackApp.get(appContext).runtimeManager.refreshLocalEndpoint(appConfigStore.load())
        }
        if (restartService && TalkbackApp.get(appContext).serviceRunning) {
            SettingsActions.startService(appContext, appConfigStore.load())
            TalkbackApp.get(appContext).runtimeManager.resetDiscovery()
        }
        return Result.success(profile)
    }

    fun syncActiveProfileFromAppConfig() {
        val config = appConfigStore.load()
        val activeId = config.activeTaskProfileId.ifBlank { profileStore.activeProfileId() }
        val profile = profileStore.getProfile(activeId) ?: return
        profileStore.saveProfile(
            profile.copy(
                sharedSecret = config.sharedSecret,
                channelId = config.defaultChannelId,
                channelDisplayName = config.channelDisplayName,
                staticPeersJson = config.staticPeersJson,
                localPriority = config.localPriority
            )
        )
    }

    fun upsertProfile(profile: TaskProfile): TaskProfile {
        profileStore.saveProfile(profile)
        return profile
    }

    fun deleteProfile(profileId: String): Boolean {
        val profiles = profileStore.listProfiles()
        if (profiles.size <= 1) return false
        return profileStore.deleteProfile(profileId)
    }

    private fun applyProfileFields(profile: TaskProfile) {
        val current = appConfigStore.load()
        appConfigStore.save(
            current.copy(
                sharedSecret = profile.sharedSecret,
                defaultChannelId = profile.channelId,
                channelDisplayName = profile.channelDisplayName,
                staticPeersJson = profile.staticPeersJson,
                activeTaskProfileId = profile.id,
                localPriority = profile.localPriority
            )
        )
    }
}
