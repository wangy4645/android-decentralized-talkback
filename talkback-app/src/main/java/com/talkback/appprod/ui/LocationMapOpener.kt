package com.talkback.appprod.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.getSystemService
import com.talkback.appprod.R
import com.talkback.core.endpointattachment.ChinaGeoOffset
import com.talkback.core.endpointattachment.LocationCoordinateSystem
import com.talkback.core.endpointattachment.LocationFixPayload
import java.net.URLEncoder
import kotlin.math.abs

object LocationMapOpener {
    private const val AMAP_PACKAGE = "com.autonavi.minimap"
    private const val BAIDU_PACKAGE = "com.baidu.BaiduMap"

    fun showChooser(context: Context, payload: LocationFixPayload) {
        val title = payload.displayTitle(context)
        val options = buildOptions(context, payload, title)
        val labels = options.map { context.getString(it.labelRes) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.message_location_open_title)
            .setItems(labels) { _, which ->
                when (val action = options[which].action) {
                    is MapAction.Launch -> launch(context, action.intent, action.useChooser)
                    MapAction.Copy -> copyCoordinates(context, payload)
                }
            }
            .show()
    }

    private fun buildOptions(
        context: Context,
        payload: LocationFixPayload,
        title: String,
    ): List<MapOption> {
        val pm = context.packageManager
        val options = mutableListOf<MapOption>()
        val amap = buildAmapIntent(context, payload, title)
        if (isPackageInstalled(pm, AMAP_PACKAGE) || amap.resolveActivity(pm) != null) {
            options += MapOption(
                R.string.message_location_map_amap,
                MapAction.Launch(amap, useChooser = false),
            )
        }
        val baidu = buildBaiduIntent(payload, title)
        if (isPackageInstalled(pm, BAIDU_PACKAGE) || baidu.resolveActivity(pm) != null) {
            options += MapOption(
                R.string.message_location_map_baidu,
                MapAction.Launch(baidu, useChooser = false),
            )
        }
        val geo = buildGeoIntent(payload, title)
        options += MapOption(
            R.string.message_location_map_other,
            MapAction.Launch(geo, useChooser = true),
        )
        options += MapOption(R.string.message_location_copy_coords, MapAction.Copy)
        return options
    }

    private fun launch(context: Context, intent: Intent, useChooser: Boolean) {
        val launchIntent = if (useChooser) {
            Intent.createChooser(intent, context.getString(R.string.message_location_open_title))
        } else {
            intent
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            context.startActivity(launchIntent)
        }.onFailure {
            Toast.makeText(context, R.string.message_location_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAmapIntent(context: Context, payload: LocationFixPayload, title: String): Intent {
        val (lat, lon) = mapCoordinatesForChinaMaps(payload)
        val appName = Uri.encode(context.getString(R.string.app_name))
        val poi = Uri.encode(title)
        val uri = Uri.parse(
            "androidamap://viewMap?sourceApplication=$appName&poiname=$poi&lat=$lat&lon=$lon&dev=0",
        )
        return Intent(Intent.ACTION_VIEW, uri).apply { setPackage(AMAP_PACKAGE) }
    }

    private fun buildBaiduIntent(payload: LocationFixPayload, title: String): Intent {
        val (lat, lon) = mapCoordinatesForChinaMaps(payload)
        val encodedTitle = Uri.encode(title)
        val uri = Uri.parse(
            "baidumap://map/marker?location=$lat,$lon&title=$encodedTitle&coord_type=gcj02&src=HumLink",
        )
        return Intent(Intent.ACTION_VIEW, uri).apply { setPackage(BAIDU_PACKAGE) }
    }

    private fun buildGeoIntent(payload: LocationFixPayload, title: String): Intent {
        val q = geoQuery(payload.latitude, payload.longitude, title)
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
    }

    private fun mapCoordinatesForChinaMaps(payload: LocationFixPayload): Pair<Double, Double> {
        return when (payload.coordinateSystem) {
            LocationCoordinateSystem.WGS84 ->
                ChinaGeoOffset.wgs84ToGcj02(payload.latitude, payload.longitude)
        }
    }

    private fun geoQuery(lat: Double, lon: Double, title: String): String {
        val coord = "${trimCoord(lat)},${trimCoord(lon)}"
        return if (title.isBlank()) {
            coord
        } else {
            "$coord(${URLEncoder.encode(title, Charsets.UTF_8.name())})"
        }
    }

    private fun trimCoord(value: Double): String {
        val rounded = (value * 1_000_000.0).toLong() / 1_000_000.0
        return if (abs(rounded - value) < 1e-9) {
            rounded.toString()
        } else {
            value.toString()
        }
    }

    private fun copyCoordinates(context: Context, payload: LocationFixPayload) {
        val text = "${payload.latitude},${payload.longitude}"
        val clipboard = context.getSystemService<ClipboardManager>()
        clipboard?.setPrimaryClip(ClipData.newPlainText("location", text))
        Toast.makeText(context, R.string.message_location_copied, Toast.LENGTH_SHORT).show()
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    private fun LocationFixPayload.displayTitle(context: Context): String {
        return label?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.message_attach_pick_location)
    }

    private data class MapOption(val labelRes: Int, val action: MapAction)

    private sealed class MapAction {
        data class Launch(val intent: Intent, val useChooser: Boolean) : MapAction()
        data object Copy : MapAction()
    }
}