package com.talkback.core.endpointattachment

/** Coordinate reference for [LocationFixPayload]. Attachments always store raw GPS as WGS84. */
enum class LocationCoordinateSystem {
    WGS84,
    ;

    companion object {
        fun fromWire(value: String?): LocationCoordinateSystem {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WGS84
        }
    }
}