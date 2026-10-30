package com.talkback.core.conference.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * P1 option-2: on screen off/on, rebind multicast transport + re-join for active conference sessions.
 * Locks (Multicast + Wifi) remain held — transport scope is not ended.
 */
object ConferenceMediaTransportScreenRebindMonitor {
    private const val MIN_REBIND_INTERVAL_MS = 2_000L

    @Volatile
    private var started = false

    private var receiver: BroadcastReceiver? = null
    private var lastRebindAtMs: Long = 0L

    /** SCREEN_OFF/ON broadcasts run on main; rebind must not block [BroadcastReceiver.onReceive]. */
    private val rebindExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "conference-transport-screen-rebind")
        }
    }

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val app = context.applicationContext
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            val r =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context?,
                        intent: Intent?,
                    ) {
                        val action = intent?.action ?: return
                        val trigger =
                            when (action) {
                                Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
                                Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
                                else -> return
                            }
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastRebindAtMs < MIN_REBIND_INTERVAL_MS) {
                            return
                        }
                        lastRebindAtMs = nowMs
                        rebindExecutor.execute {
                            val wiring = ConferenceSessionMediaBridge.wiring ?: return@execute
                            wiring.rebindAllActiveTransportsForLifecycle(trigger, nowMs)
                        }
                    }
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(r, filter)
            }
            receiver = r
            started = true
        }
    }

    /** Test / teardown hook. */
    fun stop(context: Context) {
        synchronized(this) {
            val r = receiver ?: return
            context.applicationContext.unregisterReceiver(r)
            receiver = null
            started = false
            lastRebindAtMs = 0L
        }
    }
}
