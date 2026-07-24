package com.ai.assistance.quro.core.tools

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 获取当前位置（ACCESS_FINE_LOCATION 运行时权限）。 */
class GetLocationTool : QuroTool {
    override val name = "get_location"
    override val description = "获取设备当前经纬度与精度（最后已知或单次定位），参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override val requiredPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)?.let { return it }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            var loc: Location? = null
            for (p in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )) {
                if (lm.isProviderEnabled(p)) loc = lm.getLastKnownLocation(p) ?: loc
            }
            if (loc == null) {
                val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    LocationManager.GPS_PROVIDER
                } else {
                    LocationManager.NETWORK_PROVIDER
                }
                val latch = CountDownLatch(1)
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { loc = l; latch.countDown() }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                latch.await(8, TimeUnit.SECONDS)
                runCatching { lm.removeUpdates(listener) }
            }
            loc?.let { "纬度=${it.latitude}, 经度=${it.longitude}, 精度=${it.accuracy}m" }
                ?: "无法获取位置（请确认 GPS/网络定位已开启）"
        } catch (e: Exception) {
            "获取位置失败: ${e.message}"
        }
    }
}

/** 地理编码：地址↔坐标（ACCESS_FINE_LOCATION 即可，内部用 Geocoder）。 */
class GeocodeTool : QuroTool {
    override val name = "geocode"
    override val description = "地址转坐标或坐标转地址。{\"query\":\"北京市天安门\"} 正向；{\"lat\":39.9,\"lng\":116.4} 反向。"
    override val parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"地址文本(正向)"},"lat":{"type":"number","description":"纬度(反向)"},"lng":{"type":"number","description":"经度(反向)"}}}"""
    override val requiredPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)?.let { return it }
        if (Build.VERSION.SDK_INT < 33) return "地理编码需 Android 13(API33)+"
        val jo = JSONObject(arguments)
        val gc = Geocoder(context, Locale.getDefault())
        return try {
            if (jo.has("lat") && jo.has("lng")) {
                val list = gc.getFromLocation(jo.getDouble("lat"), jo.getDouble("lng"), 3)
                if (list.isNullOrEmpty()) "（无结果）" else list.joinToString("\n") { it.getAddressLine(0) ?: "" }
            } else {
                val q = jo.optString("query", "")
                if (q.isEmpty()) return "缺少 query 或 lat/lng"
                val list = gc.getFromLocationName(q, 3)
                if (list.isNullOrEmpty()) "（无结果）" else list.joinToString("\n") {
                    "${it.latitude},${it.longitude} | ${it.getAddressLine(0) ?: ""}"
                }
            }
        } catch (e: Exception) {
            "地理编码失败: ${e.message}"
        }
    }
}
