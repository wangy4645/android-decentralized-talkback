package com.talkback.core.conference.session.integration

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * B1 / send-level desk: one-shot, time-bounded raw [pcmForEncode] capture (s16le, 48 kHz mono).
 * No gain, no Opus/RTP change. Opt-in via [applyOptionalDebugSystemProperties] only.
 */
object ShadowTxEncodePcmFieldCapture {
    const val LOG_TAG = "MEETING_MEDIA_SHADOW"
    const val PHASE_STARTED = "SHADOW_TX_ENCODE_PCM_CAPTURE_STARTED"
    const val PHASE_COMPLETE = "SHADOW_TX_ENCODE_PCM_CAPTURE_COMPLETE"
    const val PHASE_SKIPPED = "SHADOW_TX_ENCODE_PCM_CAPTURE_SKIPPED"

    private const val PROP_ARM = "debug.talkback.b1.tx_encode_pcm_capture"
    private const val PROP_DURATION_MS = "debug.talkback.b1.tx_encode_pcm_capture_ms"
    private const val DEFAULT_DURATION_MS = 45_000L
    private const val MAX_DURATION_MS = 120_000L
    private const val SAMPLE_RATE_HZ = 48_000
    private const val BYTES_PER_SAMPLE = 2

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var armedByProp: Boolean = false

    @Volatile
    private var durationMsLimit: Long = DEFAULT_DURATION_MS

    private val processCaptureConsumed = AtomicBoolean(false)

    private data class ActiveCapture(
        val sessionId: String,
        val moduleId: String,
        val startedWallMs: Long,
        val durationMs: Long,
        val frameSamples: Int,
        val pcmFile: File,
        val manifestFile: File,
        val output: BufferedOutputStream,
        var framesWritten: Long = 0,
    )

    @Volatile
    private var active: ActiveCapture? = null

    fun installApplicationContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Call once at process start (e.g. [android.app.Application.onCreate]).
     * Unset properties → no capture.
     */
    fun applyOptionalDebugSystemProperties() {
        ensureArmedFromPropIfNeeded()
    }

    private fun ensureArmedFromPropIfNeeded() {
        if (armedByProp) return
        if (!debugPropEnabled(PROP_ARM)) return
        armedByProp = true
        durationMsLimit =
            parseDurationMs(
                debugPropRaw(PROP_DURATION_MS, DEFAULT_DURATION_MS.toString()),
            )
        Log.i(
            LOG_TAG,
            "SHADOW_TX_ENCODE_PCM_CAPTURE armed durationMs=$durationMsLimit prop=$PROP_ARM",
        )
    }

    fun maybeCaptureFrame(
        sessionId: String,
        moduleId: String,
        pcmForEncode: ShortArray,
    ) {
        ensureArmedFromPropIfNeeded()
        if (!armedByProp) return
        if (processCaptureConsumed.get() && active == null) return
        val ctx = appContext ?: return

        synchronized(this) {
            val existing = active
            if (existing != null) {
                if (existing.sessionId != sessionId) return
                appendFrame(existing, pcmForEncode)
                return
            }
            if (processCaptureConsumed.get()) return
            startCapture(ctx, sessionId, moduleId, pcmForEncode)
        }
    }

    fun onSessionCaptureEnded(sessionId: String) {
        val cap = active ?: return
        if (cap.sessionId != sessionId) return
        finishCapture(cap, reason = "SESSION_END")
    }

    internal fun parseDurationMs(raw: String): Long {
        val parsed =
            raw.trim().toLongOrNull() ?: DEFAULT_DURATION_MS
        return parsed.coerceIn(1L, MAX_DURATION_MS)
    }

    internal fun shouldStopCapture(
        startedWallMs: Long,
        durationMs: Long,
        nowMs: Long,
    ): Boolean = nowMs - startedWallMs >= durationMs

    private fun startCapture(
        context: Context,
        sessionId: String,
        moduleId: String,
        firstFrame: ShortArray,
    ) {
        if (!processCaptureConsumed.compareAndSet(false, true)) return

        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val runId = "${sessionId.take(8)}_${System.currentTimeMillis()}"
        val dir = File(root, "b1-observe/tx_encode_pcm/$runId").apply { mkdirs() }
        val pcmFile = File(dir, "pcmForEncode.s16le")
        val manifestFile = File(dir, "manifest.json")

        val startedWallMs = System.currentTimeMillis()
        val durationMs = durationMsLimit
        val output = BufferedOutputStream(FileOutputStream(pcmFile))

        val cap =
            ActiveCapture(
                sessionId = sessionId,
                moduleId = moduleId,
                startedWallMs = startedWallMs,
                durationMs = durationMs,
                frameSamples = firstFrame.size,
                pcmFile = pcmFile,
                manifestFile = manifestFile,
                output = output,
            )
        active = cap

        Profile01ShadowRuntimeObservability.logPhase(
            phase = PHASE_STARTED,
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "pcmPath" to pcmFile.absolutePath,
                    "manifestPath" to manifestFile.absolutePath,
                    "durationMsLimit" to durationMs.toString(),
                    "sampleRateHz" to SAMPLE_RATE_HZ.toString(),
                    "frameSamples" to firstFrame.size.toString(),
                    "wallTimeMs" to startedWallMs.toString(),
                ),
        )

        appendFrame(cap, firstFrame)
    }

    private fun appendFrame(
        cap: ActiveCapture,
        pcmForEncode: ShortArray,
    ) {
        try {
            val bytes = shortsToLittleEndianBytes(pcmForEncode)
            cap.output.write(bytes)
            cap.framesWritten++
            val now = System.currentTimeMillis()
            if (shouldStopCapture(cap.startedWallMs, cap.durationMs, now)) {
                finishCapture(cap, reason = "DURATION_LIMIT")
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "SHADOW_TX_ENCODE_PCM_CAPTURE write failed: ${t.message}", t)
            finishCapture(cap, reason = "WRITE_ERROR:${t.javaClass.simpleName}")
        }
    }

    private fun finishCapture(
        cap: ActiveCapture,
        reason: String,
    ) {
        if (active !== cap) return
        active = null
        try {
            cap.output.flush()
            cap.output.close()
        } catch (_: Throwable) {
        }

        val endedMs = System.currentTimeMillis()
        val totalSamples = cap.framesWritten * cap.frameSamples

        val manifest =
            JSONObject()
                .put("kind", "pcmForEncode_pre_opus")
                .put("sessionId", cap.sessionId)
                .put("moduleId", cap.moduleId)
                .put("sampleRateHz", SAMPLE_RATE_HZ)
                .put("channels", 1)
                .put("encoding", "s16le")
                .put("frameSamples", cap.frameSamples)
                .put("framesWritten", cap.framesWritten)
                .put("totalSamples", totalSamples)
                .put("startedWallMs", cap.startedWallMs)
                .put("endedWallMs", endedMs)
                .put("durationMsLimit", cap.durationMs)
                .put("stopReason", reason)
                .put("pcmFile", cap.pcmFile.name)
        cap.manifestFile.writeText(manifest.toString(2))

        Profile01ShadowRuntimeObservability.logPhase(
            phase = PHASE_COMPLETE,
            sessionId = cap.sessionId,
            moduleId = cap.moduleId,
            fields =
                mapOf(
                    "pcmPath" to cap.pcmFile.absolutePath,
                    "manifestPath" to cap.manifestFile.absolutePath,
                    "framesWritten" to cap.framesWritten.toString(),
                    "bytesWritten" to cap.pcmFile.length().toString(),
                    "stopReason" to reason,
                    "wallTimeMs" to endedMs.toString(),
                ),
        )
    }

    private fun shortsToLittleEndianBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * BYTES_PER_SAMPLE)
        var i = 0
        for (s in samples) {
            val v = s.toInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun debugPropEnabled(key: String): Boolean {
        val value = debugPropRaw(key, "0")
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    private fun debugPropRaw(
        key: String,
        default: String,
    ): String =
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get =
                clazz.getMethod(
                    "get",
                    String::class.java,
                    String::class.java,
                )
            get.invoke(null, key, default) as? String ?: default
        } catch (_: Throwable) {
            default
        }

    /** Desk / tests: reset one-shot gate. */
    internal fun resetForDeskTest() {
        active?.output?.close()
        active = null
        processCaptureConsumed.set(false)
        armedByProp = false
    }
}
