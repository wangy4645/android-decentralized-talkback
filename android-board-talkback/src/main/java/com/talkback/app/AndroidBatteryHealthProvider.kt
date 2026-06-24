package com.talkback.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.talkback.core.session.LocalDeviceHealthProvider
import com.talkback.core.session.LocalDeviceHealthSnapshot

class AndroidBatteryHealthProvider(context: Context) : LocalDeviceHealthProvider {
    private val appContext = context.applicationContext
    private val onlineSinceMs = System.currentTimeMillis()

    override fun snapshot(): LocalDeviceHealthSnapshot {
        val batteryStatus = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val percent = when {
            level < 0 || scale <= 0 -> 100
            else -> (level * 100 / scale).coerceIn(0, 100)
        }
        return LocalDeviceHealthSnapshot(
            charging = charging,
            batteryPercent = percent,
            onlineSinceMs = onlineSinceMs
        )
    }
}
