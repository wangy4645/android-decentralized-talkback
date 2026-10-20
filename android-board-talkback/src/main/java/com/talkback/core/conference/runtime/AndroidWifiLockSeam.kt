package com.talkback.core.conference.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Conference media transport-scope Wi-Fi lock (P1 / option 1).
 *
 * Paired with [AndroidWifiMulticastLockSeam] at scope begin/end. Silence / empty Top-K
 * MUST NOT release. Does not mint authority.
 */
class AndroidWifiLockSeam(
    wifiManager: WifiManager,
    tag: String = TAG,
) : WifiLockSeam {
    @Suppress("DEPRECATION")
    private val lock: WifiManager.WifiLock =
        (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, tag)
            } else {
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
            }
        ).apply { setReferenceCounted(false) }

    private var acquires = 0
    private var releases = 0

    override fun acquire(): Boolean {
        return try {
            if (!lock.isHeld) {
                lock.acquire()
            }
            if (lock.isHeld) {
                acquires += 1
                true
            } else {
                false
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun release() {
        if (!lock.isHeld) return
        try {
            lock.release()
        } finally {
            releases += 1
        }
    }

    override fun isHeld(): Boolean = lock.isHeld

    override fun acquireCount(): Int = acquires

    override fun releaseCount(): Int = releases

    companion object {
        const val TAG = "talkback.conference.media.wifi"

        fun from(context: Context): AndroidWifiLockSeam {
            val wifi =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return AndroidWifiLockSeam(wifi)
        }
    }
}
