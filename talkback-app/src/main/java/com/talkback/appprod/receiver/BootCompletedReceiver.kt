package com.talkback.appprod.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.talkback.appprod.data.AppConfigStore
import com.talkback.appprod.data.TaskProfileManager
import com.talkback.appprod.service.TalkbackForegroundService
import com.talkback.appprod.ui.SettingsActions

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        TaskProfileManager(context).ensureInitialized()
        val config = AppConfigStore(context).load()
        if (!config.autoStartOnBoot) return
        if (SettingsActions.validateSecret(config.sharedSecret) != null) return

        val startIntent = Intent(context, TalkbackForegroundService::class.java).apply {
            putExtra(TalkbackForegroundService.EXTRA_MODULE_ID, config.moduleId)
            putExtra(TalkbackForegroundService.EXTRA_ENDPOINT_ID, config.endpointId)
            putExtra(TalkbackForegroundService.EXTRA_SIGNALING_PORT, config.signalingPort)
            putExtra(TalkbackForegroundService.EXTRA_AUTO_REDIAL, config.autoRedial)
            putExtra(TalkbackForegroundService.EXTRA_AUTO_START_ON_BOOT, config.autoStartOnBoot)
            putExtra(TalkbackForegroundService.EXTRA_SHARED_SECRET, config.sharedSecret)
            putExtra(TalkbackForegroundService.EXTRA_ALLOWED_MODULES, config.allowedModuleIds.joinToString(","))
            putExtra(TalkbackForegroundService.EXTRA_STATIC_PEERS_JSON, config.staticPeersJson)
        }
        ContextCompat.startForegroundService(context, startIntent)
    }
}
