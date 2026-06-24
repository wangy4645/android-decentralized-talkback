package com.talkback.appprod.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.talkback.appprod.data.AppConfig
import com.talkback.appprod.service.TalkbackForegroundService

object SettingsActions {
    fun validateInput(moduleId: String, endpointId: String, port: Int?): String? {
        if (!moduleId.matches(Regex("^[A-Z0-9]{2,16}$"))) {
            return "moduleId must be 2-16 chars [A-Z0-9]"
        }
        if (!endpointId.matches(Regex("^[A-Z0-9]{1,16}$"))) {
            return "endpointId must be 1-16 chars [A-Z0-9]"
        }
        if (port == null || port !in 1024..65535) {
            return "port must be in range 1024-65535"
        }
        return null
    }

    fun validatePeerModuleId(moduleId: String, localModuleId: String): String? {
        val normalized = moduleId.trim().uppercase()
        if (!normalized.matches(Regex("^[A-Z0-9]{2,16}$"))) {
            return "peer moduleId must be 2-16 chars [A-Z0-9]"
        }
        if (normalized == localModuleId.trim().uppercase()) {
            return "peer moduleId cannot match this device"
        }
        return null
    }

    fun validatePeerHost(host: String): String? {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return "peer IP is required"
        val ipv4 = Regex("^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$")
        val hostname = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,62}[A-Za-z0-9])?$")
        if (!trimmed.matches(ipv4) && !trimmed.matches(hostname)) {
            return "peer IP format is invalid"
        }
        return null
    }

    fun validatePeerPort(port: Int?): String? {
        if (port == null || port !in 1024..65535) {
            return "peer port must be in range 1024-65535"
        }
        return null
    }

    fun validateSecret(secret: String): String? {
        if (secret.trim().isEmpty()) {
            return "task shared secret is required"
        }
        return null
    }

    fun validateServiceConfig(config: AppConfig): String? {
        validateInput(config.moduleId, config.endpointId, config.signalingPort)?.let { return it }
        validateSecret(config.sharedSecret)?.let { return it }
        return null
    }

    fun startService(context: Context, config: AppConfig) {
        val intent = Intent(context, TalkbackForegroundService::class.java).apply {
            putExtra(TalkbackForegroundService.EXTRA_MODULE_ID, config.moduleId)
            putExtra(TalkbackForegroundService.EXTRA_ENDPOINT_ID, config.endpointId)
            putExtra(TalkbackForegroundService.EXTRA_SIGNALING_PORT, config.signalingPort)
            putExtra(TalkbackForegroundService.EXTRA_AUTO_REDIAL, config.autoRedial)
            putExtra(TalkbackForegroundService.EXTRA_AUTO_START_ON_BOOT, config.autoStartOnBoot)
            putExtra(TalkbackForegroundService.EXTRA_SHARED_SECRET, config.sharedSecret)
            putExtra(
                TalkbackForegroundService.EXTRA_ALLOWED_MODULES,
                config.effectiveAllowedModuleIds().joinToString(",")
            )
            putExtra(TalkbackForegroundService.EXTRA_STATIC_PEERS_JSON, config.staticPeersJson)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopService(context: Context) {
        context.startService(
            Intent(context, TalkbackForegroundService::class.java).apply {
                action = TalkbackForegroundService.ACTION_STOP_SERVICE
            }
        )
    }
}
