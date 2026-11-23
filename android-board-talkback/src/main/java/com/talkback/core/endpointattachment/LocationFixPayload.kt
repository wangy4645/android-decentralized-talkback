package com.talkback.core.endpointattachment

import org.json.JSONObject

/** Compact GPS fix carried as a V2 attachment body. */
data class LocationFixPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val timestampMs: Long,
    val provider: String,
    val label: String? = null,
    val coordinateSystem: LocationCoordinateSystem = LocationCoordinateSystem.WGS84,
) {
    fun encode(): ByteArray = JSONObject()
        .put("v", 1)
        .put("lat", latitude)
        .put("lon", longitude)
        .put("accM", accuracyMeters?.toDouble() ?: JSONObject.NULL)
        .put("altM", altitudeMeters ?: JSONObject.NULL)
        .put("tsMs", timestampMs)
        .put("provider", provider)
        .put("label", label?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("crs", coordinateSystem.name)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        const val MIME_TYPE = "application/vnd.talkback.location+json"
        const val FILE_NAME = "location.json"

        fun decode(bytes: ByteArray): LocationFixPayload? {
            if (bytes.isEmpty()) return null
            return runCatching {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val lat = json.optDouble("lat", Double.NaN)
                val lon = json.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                val accRaw = json.opt("accM")
                val acc = when {
                    accRaw == null || accRaw == JSONObject.NULL -> null
                    else -> accRaw.toString().toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
                }
                val altRaw = json.opt("altM")
                val alt = when {
                    altRaw == null || altRaw == JSONObject.NULL -> null
                    else -> altRaw.toString().toDoubleOrNull()?.takeIf { it.isFinite() }
                }
                val labelRaw = json.opt("label")
                val label = when {
                    labelRaw == null || labelRaw == JSONObject.NULL -> null
                    else -> labelRaw.toString().trim().takeIf { it.isNotEmpty() }
                }
                LocationFixPayload(
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = acc,
                    altitudeMeters = alt,
                    timestampMs = json.optLong("tsMs", 0L),
                    provider = json.optString("provider", "gps").ifBlank { "gps" },
                    label = label,
                    coordinateSystem = LocationCoordinateSystem.fromWire(
                        json.optString("crs").takeIf { json.has("crs") && it.isNotBlank() },
                    ),
                )
            }.getOrNull()
        }
    }
}