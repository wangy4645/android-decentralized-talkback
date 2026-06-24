package com.talkback.appprod.data

import android.content.Context
import com.talkback.core.model.EndpointPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskProfileStoreTest {
    private lateinit var context: Context
    private lateinit var appConfigStore: AppConfigStore
    private lateinit var profileStore: TaskProfileStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("talkback_prod_config", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("talkback_task_profiles", Context.MODE_PRIVATE).edit().clear().apply()
        appConfigStore = AppConfigStore(context)
        profileStore = TaskProfileStore(context)
    }

    @Test
    fun migrateFromAppConfig_createsDefaultProfile() {
        appConfigStore.save(
            appConfigStore.load().copy(
                sharedSecret = "migrate-secret",
                defaultChannelId = "CH-MIG",
                channelDisplayName = "Migration Team",
                staticPeersJson = "[]"
            )
        )
        profileStore.migrateFromAppConfigIfNeeded(appConfigStore)
        val profiles = profileStore.listProfiles()
        assertEquals(1, profiles.size)
        assertEquals("migrate-secret", profiles.single().sharedSecret)
        assertEquals("CH-MIG", profiles.single().channelId)
        assertNotNull(profileStore.activeProfile())
        val config = appConfigStore.load()
        assertEquals(profiles.single().id, config.activeTaskProfileId)
    }

    @Test
    fun saveProfile_roundTripsLocalPriority() {
        val profile = TaskProfile.createNew("Priority Task", "secret-p").copy(
            localPriority = EndpointPriority.EMERGENCY
        )
        profileStore.saveProfiles(listOf(profile))
        val loaded = profileStore.listProfiles().single()
        assertEquals(EndpointPriority.EMERGENCY, loaded.localPriority)
    }

    @Test
    fun setActiveProfileId_updatesActiveSelection() {
        val profile = TaskProfile.createNew("B", "secret-b")
        profileStore.saveProfiles(listOf(profile))
        profileStore.setActiveProfileId(profile.id)
        assertEquals(profile.id, profileStore.activeProfileId())
    }
}
