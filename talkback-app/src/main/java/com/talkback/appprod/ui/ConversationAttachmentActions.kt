package com.talkback.appprod.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.talkback.appprod.R
import com.talkback.appprod.endpointtext.StreamingFileHasher
import com.talkback.core.endpointattachment.AttachmentKind
import com.talkback.core.endpointattachment.LocationCoordinateSystem
import com.talkback.core.endpointattachment.LocationFixPayload
import com.talkback.core.endpointattachment.bulk.AttachmentSizePolicy
import java.io.File

/**
 * Registers pickers / voice capture for conversation screens only.
 */
class ConversationAttachmentActions(
    private val fragment: Fragment,
    private val onBytesReady: (
        bytes: ByteArray,
        kind: AttachmentKind,
        mimeType: String,
        fileName: String
    ) -> Unit,
    private val onLargeUriReady: (
        uri: Uri,
        kind: AttachmentKind,
        mimeType: String,
        fileName: String,
        sizeBytes: Long
    ) -> Unit,
) {
    private val context: Context = fragment.requireContext()

    private var voiceRecorder: MediaRecorder? = null
    private var voiceOutputFile: File? = null
    private var voiceStartedAtMs: Long = 0L

    private val pickImage: ActivityResultLauncher<Intent> =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finishPickerSession()
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            deliverUri(uri, AttachmentKind.IMAGE, defaultName = "photo.jpg")
        }

    private val pickDocument: ActivityResultLauncher<Intent> =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finishPickerSession()
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            deliverUri(uri, AttachmentKind.FILE, defaultName = "file.bin")
        }

    private val requestLocationPermission: ActivityResultLauncher<String> =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startGpsFix()
            } else {
                Toast.makeText(context, R.string.message_location_need_permission, Toast.LENGTH_SHORT).show()
            }
        }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var gpsListener: LocationListener? = null
    private var gpsTimeout: Runnable? = null
    private var geocodeFallback: Runnable? = null
    private var bestGpsFix: Location? = null

    fun pickImage() {
        beginPickerSession()
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        runCatching { pickImage.launch(intent) }.onFailure { finishPickerSession() }
    }

    fun pickFile() {
        beginPickerSession()
        launchSystemFilePicker()
    }

    fun shareLocation() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startGpsFix()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun launchSystemFilePicker() {
        val intent = buildDocumentPickIntent()
        runCatching {
            pickDocument.launch(intent)
        }.onFailure {
            finishPickerSession()
            Toast.makeText(context, R.string.message_attach_file_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Document-oriented SAF: no catch-all mime (Huawei opens image/Screenshot grid). Images use [pickImage]. */
    private fun buildDocumentPickIntent(): Intent {
        val mimeTypes = arrayOf(
            "application/*",
            "text/*",
        )
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentPickStartUri())
            }
        }
    }

    private fun documentPickStartUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
        }
    }

    fun isVoiceRecording(): Boolean = voiceRecorder != null

    /** Hold-to-talk: returns false if recorder could not start. */
    fun startVoiceRecording(): Boolean {
        if (voiceRecorder != null) return true
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        voiceOutputFile = file
        return runCatching {
            voiceRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            voiceStartedAtMs = System.currentTimeMillis()
            true
        }.getOrElse {
            releaseVoiceRecorder(deleteFile = true)
            Toast.makeText(context, R.string.message_voice_record_start_failed, Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** Release finger: stop and send if long enough. */
    fun finishVoiceRecordingAndSend() {
        val file = voiceOutputFile ?: return
        val durationMs = System.currentTimeMillis() - voiceStartedAtMs
        releaseVoiceRecorder(deleteFile = false)
        if (durationMs < MIN_VOICE_MS) {
            file.delete()
            Toast.makeText(context, R.string.message_voice_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        val bytes = file.readBytes()
        onBytesReady(bytes, AttachmentKind.VOICE, "audio/mp4", file.name)
    }

    fun cancelVoiceRecording() {
        releaseVoiceRecorder(deleteFile = true)
    }

    fun release() {
        finishPickerSession()
        stopGpsFix()
        releaseVoiceRecorder(deleteFile = true)
    }

    private fun startGpsFix() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(context, R.string.message_location_gps_off, Toast.LENGTH_SHORT).show()
            return
        }
        if (!manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(context, R.string.message_location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        stopGpsFix()
        bestGpsFix = null
        Toast.makeText(context, R.string.message_location_fixing, Toast.LENGTH_SHORT).show()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.provider != null &&
                    location.provider != LocationManager.GPS_PROVIDER
                ) {
                    return
                }
                val currentBest = bestGpsFix
                if (currentBest == null ||
                    (location.hasAccuracy() &&
                        (!currentBest.hasAccuracy() || location.accuracy < currentBest.accuracy))
                ) {
                    bestGpsFix = location
                }
                val acc = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
                if (acc <= ACCEPT_ACCURACY_M) {
                    completeGpsFix(location)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        gpsListener = listener
        val ok = runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }.isSuccess
        if (!ok) {
            stopGpsFix()
            Toast.makeText(context, R.string.message_location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val timeout = Runnable {
            val best = bestGpsFix
            if (best != null && best.hasAccuracy() && best.accuracy <= TIMEOUT_ACCEPT_ACCURACY_M) {
                completeGpsFix(best)
            } else {
                stopGpsFix()
                Toast.makeText(context, R.string.message_location_timeout, Toast.LENGTH_SHORT).show()
            }
        }
        gpsTimeout = timeout
        mainHandler.postDelayed(timeout, GPS_TIMEOUT_MS)
    }

    private fun completeGpsFix(location: Location) {
        stopGpsFix()
        var delivered = false
        fun deliver(label: String?) {
            if (delivered) return
            delivered = true
            geocodeFallback?.let { mainHandler.removeCallbacks(it) }
            geocodeFallback = null
            val payload = LocationFixPayload(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                provider = location.provider ?: LocationManager.GPS_PROVIDER,
                label = label,
                coordinateSystem = LocationCoordinateSystem.WGS84,
            )
            onBytesReady(
                payload.encode(),
                AttachmentKind.LOCATION,
                LocationFixPayload.MIME_TYPE,
                LocationFixPayload.FILE_NAME,
            )
        }
        val fallback = Runnable { deliver(null) }
        geocodeFallback = fallback
        mainHandler.postDelayed(fallback, GEOCODE_TIMEOUT_MS)
        LocationReverseGeocoder.resolve(context, location.latitude, location.longitude) { label ->
            mainHandler.post { deliver(label) }
        }
    }

    private fun stopGpsFix() {
        gpsTimeout?.let { mainHandler.removeCallbacks(it) }
        gpsTimeout = null
        val listener = gpsListener ?: return
        gpsListener = null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { manager.removeUpdates(listener) }
    }

    private fun beginPickerSession() {
        ConversationAttachmentPickerGate.setActive(true)
    }

    private fun finishPickerSession() {
        ConversationAttachmentPickerGate.setActive(false)
    }

    private fun releaseVoiceRecorder(deleteFile: Boolean) {
        runCatching { voiceRecorder?.stop() }
        runCatching { voiceRecorder?.release() }
        voiceRecorder = null
        voiceOutputFile?.let { file ->
            if (deleteFile) file.delete()
        }
        voiceOutputFile = null
        voiceStartedAtMs = 0L
    }

    private fun deliverUri(uri: Uri, kind: AttachmentKind, defaultName: String) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = queryDisplayName(uri) ?: defaultName
        val size = StreamingFileHasher.querySize(context, uri)
        when (AttachmentSizePolicy.route(size ?: -1L)) {
            AttachmentSizePolicy.Route.REJECT_TOO_LARGE ->
                Toast.makeText(context, R.string.message_attachment_too_large, Toast.LENGTH_SHORT).show()
            AttachmentSizePolicy.Route.REJECT_EMPTY -> Unit
            AttachmentSizePolicy.Route.DC_BULK -> {
                if (size != null) {
                    onLargeUriReady(uri, kind, mime.ifBlank { "application/octet-stream" }, name, size)
                }
            }
            AttachmentSizePolicy.Route.V2_UDP -> {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    onBytesReady(bytes, kind, mime.ifBlank { "application/octet-stream" }, name)
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) {
                return cursor.getString(idx)
            }
        }
        return null
    }

    companion object {
        private const val MIN_VOICE_MS = 600L
        private const val GEOCODE_TIMEOUT_MS = 2_500L
        private const val GPS_TIMEOUT_MS = 45_000L
        private const val ACCEPT_ACCURACY_M = 20f
        private const val TIMEOUT_ACCEPT_ACCURACY_M = 75f
    }
}
