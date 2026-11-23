package com.talkback.appprod.ui

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object LocationReverseGeocoder {
    private val executor = Executors.newSingleThreadExecutor()

    fun resolve(
        context: Context,
        latitude: Double,
        longitude: Double,
        onResult: (String?) -> Unit,
    ) {
        if (!Geocoder.isPresent()) {
            onResult(null)
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val label = runCatching {
                resolveSync(appContext, latitude, longitude)
            }.getOrNull()
            onResult(label?.takeIf { it.isNotBlank() })
        }
    }

    private fun resolveSync(context: Context, latitude: Double, longitude: Double): String? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var result: String? = null
            val latch = CountDownLatch(1)
            geocoder.getFromLocation(latitude, longitude, 1) { list ->
                result = formatAddress(list.firstOrNull())
                latch.countDown()
            }
            latch.await(2_500, TimeUnit.MILLISECONDS)
            result
        } else {
            @Suppress("DEPRECATION")
            formatAddress(geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull())
        }
    }

    private fun formatAddress(address: Address?): String? {
        if (address == null) return null
        val line = address.getAddressLine(0)?.trim()
        if (!line.isNullOrBlank()) return line
        return buildList {
            address.featureName?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            address.thoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            address.subLocality?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            address.locality?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            address.adminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }.joinToString("")
            .ifBlank {
                listOfNotNull(address.locality, address.adminArea, address.countryName)
                    .joinToString(" ")
                    .trim()
                    .ifBlank { null }
            }
    }
}