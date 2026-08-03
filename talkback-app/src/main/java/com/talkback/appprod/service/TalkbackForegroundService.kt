package com.talkback.appprod.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.talkback.appprod.BuildConfig
import com.talkback.appprod.R
import com.talkback.appprod.TalkbackApp
import com.talkback.appprod.data.AppConfigStore
import com.talkback.appprod.debug.DebugHarnessBroadcastDispatcher
import com.talkback.appprod.runtime.TalkbackRuntimeManager
import com.talkback.appprod.ui.SettingsActions
import java.util.concurrent.Executors

class TalkbackForegroundService : Service() {
    private lateinit var runtimeManager: TalkbackRuntimeManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceStopped = false
    private var pr52cDebugReceiverRegistered = false

    // Cached pool: one hung debug work must not stall later PendingResult finish paths.
    private val debugBroadcastExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "talkback-debug-bcast").apply { isDaemon = true }
    }

    private val pr52cDebugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!BuildConfig.DEBUG || intent == null) return
            val action = intent.action ?: return
            val remote = intent.getStringExtra(EXTRA_DEBUG_REMOTE) ?: "M03"
            val ttlMs = intent.getLongExtra(
                EXTRA_DEBUG_TTL_MS,
                180_000L
            )
            // Delivered marker on receive path (before async work) for harness integrity.
            android.util.Log.i(DEBUG_LOG_TAG, "DEBUG_DISPATCH_DELIVERED action=$action remote=$remote")
            val pending = goAsync()
            DebugHarnessBroadcastDispatcher.schedule(
                executor = debugBroadcastExecutor,
                finishPending = { pending.finish() },
                action = action,
                log = { line -> android.util.Log.i(DEBUG_LOG_TAG, line) },
                work = {
                    val runtime = runtimeManager.getRuntime()
                    if (runtime == null) {
                        android.util.Log.i(
                            DEBUG_LOG_TAG,
                            "DEBUG_DISPATCH_SKIPPED action=$action reason=runtime_unavailable"
                        )
                        return@schedule DebugHarnessBroadcastDispatcher.Outcome.SKIPPED
                    }
                    val ok = when (action) {
                        ACTION_DEBUG_PR52C_CREATE ->
                            runtime.debugPr52cCreateDeferredIntent(remote) != null
                        ACTION_DEBUG_PR52C_BLOCK_DISPATCH ->
                            runtime.debugPr52cBlockDispatch(remote)
                        ACTION_DEBUG_PR52C_NEG_EXECUTE ->
                            runtime.debugPr52cFireNegotiationCanExecute(remote)
                        ACTION_DEBUG_PR52C_RELEASE_DISPATCH ->
                            runtime.debugPr52cReleaseDispatch(remote)
                        ACTION_DEBUG_EXPLICIT_SUPERSEDE ->
                            runtime.debugExplicitSupersedeDeferredIntent(remote)
                        ACTION_DEBUG_D1_ARM_DROP_INGRESS ->
                            runtime.debugD1ArmDropRecoveryOfferIngress()
                        ACTION_DEBUG_D1_CLEAR_INGRESS_MISS ->
                            runtime.debugD1ClearIngressMissInjection()
                        ACTION_DEBUG_SUPPRESS_SUCCESSOR_ARM ->
                            runtime.debugSuppressSuccessorAttemptArm(remote, ttlMs)
                        ACTION_DEBUG_SUPPRESS_SUCCESSOR_CLEAR ->
                            runtime.debugSuppressSuccessorAttemptClear(remote)
                        else -> false
                    }
                    if (ok) {
                        DebugHarnessBroadcastDispatcher.Outcome.COMPLETED
                    } else {
                        // Timed-out / skipped paths already logged by coordinator.
                        DebugHarnessBroadcastDispatcher.Outcome.SKIPPED
                    }
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceStopped = false
        runtimeManager = TalkbackApp.get(this).runtimeManager
        ensureChannel()
        startForeground(NOTIFY_ID, buildNotification(getString(R.string.notification_running)))
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter().apply {
                addAction(ACTION_DEBUG_PR52C_CREATE)
                addAction(ACTION_DEBUG_PR52C_BLOCK_DISPATCH)
                addAction(ACTION_DEBUG_PR52C_NEG_EXECUTE)
                addAction(ACTION_DEBUG_PR52C_RELEASE_DISPATCH)
                addAction(ACTION_DEBUG_EXPLICIT_SUPERSEDE)
                addAction(ACTION_DEBUG_D1_ARM_DROP_INGRESS)
                addAction(ACTION_DEBUG_D1_CLEAR_INGRESS_MISS)
                addAction(ACTION_DEBUG_SUPPRESS_SUCCESSOR_ARM)
                addAction(ACTION_DEBUG_SUPPRESS_SUCCESSOR_CLEAR)
            }
            // DEBUG only: exported so adb shell (uid 2000) can trigger field injection.
            ContextCompat.registerReceiver(
                this,
                pr52cDebugReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            pr52cDebugReceiverRegistered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopServiceInternal("Stopped by user action")
            stopSelf()
            return START_NOT_STICKY
        }

        val config = AppConfigStore(this).load()
        SettingsActions.validateSecret(config.sharedSecret)?.let { reason ->
            TalkbackApp.get(this).serviceRunning = false
            sendServiceState(STATE_ERROR, "Start failed: $reason")
            return START_NOT_STICKY
        }
        if (runtimeManager.isRunning()) {
            sendServiceState(STATE_RUNNING, "Service already running for ${config.moduleId}-${config.endpointId}")
            return START_STICKY
        }
        acquireWakeLock()
        runtimeManager.start(config).onSuccess {
            TalkbackApp.get(this).serviceRunning = true
            sendServiceState(STATE_RUNNING, "Service running for ${config.moduleId}-${config.endpointId}")
        }.onFailure {
            TalkbackApp.get(this).serviceRunning = false
            sendServiceState(STATE_ERROR, "Start failed: ${it.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (pr52cDebugReceiverRegistered) {
            runCatching { unregisterReceiver(pr52cDebugReceiver) }
            pr52cDebugReceiverRegistered = false
        }
        runCatching { debugBroadcastExecutor.shutdownNow() }
        stopServiceInternal("Service destroyed")
        super.onDestroy()
    }

    private fun stopServiceInternal(reason: String) {
        if (serviceStopped) return
        serviceStopped = true
        runCatching { runtimeManager.stop() }
        TalkbackApp.get(this).serviceRunning = false
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        sendServiceState(STATE_STOPPED, reason)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "talkback:ptt").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = Intent(this, TalkbackForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun sendServiceState(state: String, detail: String) {
        val intent = Intent(ACTION_SERVICE_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_SERVICE_STATE, state)
            putExtra(EXTRA_SERVICE_DETAIL, detail)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val CHANNEL_ID = "talkback_service"
        private const val DEBUG_LOG_TAG = "Talkback"
        private const val NOTIFY_ID = 3001

        const val EXTRA_MODULE_ID = "moduleId"
        const val EXTRA_ENDPOINT_ID = "endpointId"
        const val EXTRA_SIGNALING_PORT = "signalingPort"
        const val EXTRA_AUTO_REDIAL = "autoRedial"
        const val EXTRA_AUTO_START_ON_BOOT = "autoStartOnBoot"
        const val EXTRA_SHARED_SECRET = "sharedSecret"
        const val EXTRA_ALLOWED_MODULES = "allowedModuleIds"
        const val EXTRA_STATIC_PEERS_JSON = "staticPeersJson"

        const val ACTION_STOP_SERVICE = "com.talkback.appprod.action.STOP_SERVICE"
        const val ACTION_SERVICE_STATE = "com.talkback.appprod.action.SERVICE_STATE"

        const val EXTRA_SERVICE_STATE = "serviceState"
        const val EXTRA_SERVICE_DETAIL = "serviceDetail"

        const val STATE_RUNNING = "RUNNING"
        const val STATE_STOPPED = "STOPPED"
        const val STATE_ERROR = "ERROR"

        const val EXTRA_DEBUG_REMOTE = "remote"
        const val EXTRA_DEBUG_TTL_MS = "ttlMs"
        const val ACTION_DEBUG_PR52C_CREATE = "com.talkback.appprod.debug.PR52C_CREATE"
        const val ACTION_DEBUG_PR52C_BLOCK_DISPATCH = "com.talkback.appprod.debug.PR52C_BLOCK_DISPATCH"
        const val ACTION_DEBUG_PR52C_NEG_EXECUTE = "com.talkback.appprod.debug.PR52C_NEG_EXECUTE"
        const val ACTION_DEBUG_PR52C_RELEASE_DISPATCH = "com.talkback.appprod.debug.PR52C_RELEASE_DISPATCH"
        /** ADR-0022 E.16.2 Phase-3A FA-3 Option A. */
        const val ACTION_DEBUG_EXPLICIT_SUPERSEDE = "com.talkback.appprod.debug.DEBUG_EXPLICIT_SUPERSEDE"
        /** D1 Option A: arm recovery-offer ingress drop on this device (typically M03). */
        const val ACTION_DEBUG_D1_ARM_DROP_INGRESS = "com.talkback.appprod.debug.D1_ARM_DROP_INGRESS"
        const val ACTION_DEBUG_D1_CLEAR_INGRESS_MISS = "com.talkback.appprod.debug.D1_CLEAR_INGRESS_MISS"
        /** Harness: suppress successor obligation admission (Attempt-4c-S). */
        const val ACTION_DEBUG_SUPPRESS_SUCCESSOR_ARM =
            "com.talkback.appprod.debug.SUPPRESS_SUCCESSOR_ATTEMPT_ARM"
        const val ACTION_DEBUG_SUPPRESS_SUCCESSOR_CLEAR =
            "com.talkback.appprod.debug.SUPPRESS_SUCCESSOR_ATTEMPT_CLEAR"
    }
}
