package com.talkback.core.conference.runtime

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Product C-IG-01 MulticastLock seam: real [WifiManager.MulticastLock].
 *
 * Ownership is Conference media transport scope (R13 / Q7). Not reference-counted:
 * one acquire at scope begin, one release at scope end. Silence / V=0 / empty
 * Top-K MUST NOT call [release].
 *
 * Does not mint Membership / Source authority.
 */
class AndroidWifiMulticastLockSeam(
    wifiManager: WifiManager,
    tag: String = TAG,
) : MulticastLockSeam {
    private val lock: WifiManager.MulticastLock =
        wifiManager.createMulticastLock(tag).apply { setReferenceCounted(false) }

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
        const val TAG = "talkback.conference.media"

        fun from(context: Context): AndroidWifiMulticastLockSeam {
            val wifi =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return AndroidWifiMulticastLockSeam(wifi)
        }
    }
}
