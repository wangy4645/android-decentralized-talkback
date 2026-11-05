package com.talkback.appprod.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.talkback.appprod.R
import com.talkback.appprod.data.AppConfig
import com.talkback.appprod.service.TalkbackForegroundService

object SettingsActions {
    @StringRes
    fun validateInput(moduleId: String, endpointId: String, port: Int?): Int? {
        if (!moduleId.matches(Regex("^[A-Z0-9]{2,16}$"))) {
            return R.string.settings_validation_module_id
        }
        if (!endpointId.matches(Regex("^[A-Z0-9]{1,16}$"))) {
            return R.string.settings_validation_endpoint_id
        }
        if (port == null || port !in 1024..65535) {
            return R.string.settings_validation_port
        }
        return null
    }

    @StringRes
    fun validatePeerModuleId(moduleId: String, localModuleId: String): Int? {
        val normalized = moduleId.trim().uppercase()
        if (!normalized.matches(Regex("^[A-Z0-9]{2,16}$"))) {
            return R.string.settings_validation_peer_module_id
        }
        if (normalized == localModuleId.trim().uppercase()) {
            return R.string.settings_validation_peer_module_id_self
        }
        return null
    }

    @StringRes
    fun validatePeerHost(host: String): Int? {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return R.string.settings_validation_peer_host_required
        val ipv4 = Regex("^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$")
        val hostname = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,62}[A-Za-z0-9])?$")
        if (!trimmed.matches(ipv4) && !trimmed.matches(hostname)) {
            return R.string.settings_validation_peer_host_invalid
        }
        return null
    }

    @StringRes
    fun validatePeerPort(port: Int?): Int? {
        if (port == null || port !in 1024..65535) {
            return R.string.settings_validation_peer_port
        }
        return null
    }

    @StringRes
    fun validateSecret(secret: String): Int? {
        if (secret.trim().isEmpty()) {
            return R.string.settings_validation_secret_required
        }
        return null
    }

    @StringRes
    fun validateServiceConfig(config: AppConfig): Int? {
        validateInput(config.moduleId, config.endpointId, config.signalingPort)?.let { return it }
        validateSecret(config.sharedSecret)?.let { return it }
        return null
    }

    fun validationMessage(context: Context, @StringRes messageRes: Int): String =
        context.getString(messageRes)

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
        ContextCompat.startForegroundService(
            context,
            Intent(context, TalkbackForegroundService::class.java).apply {
                action = TalkbackForegroundService.ACTION_STOP_SERVICE
            }
        )
    }
}
