package com.ai.assistance.quro.core.miniapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 位置模块：native.location.getLocation()。
 * 移植自 MiniAppFramework（com.miniapp），去品牌化为 QuroAI 的 MiniAppBridgeModule 协议。
 * 运行时需 ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION 权限（由宿主在 Manifest 中声明）；
 * 未授权时返回错误而非崩溃。
 */
class LocationModule(private val context: Context) : MiniAppBridgeModule {
    override val name = "location"

    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "getLocation" -> getLocation(callback)
            else -> callback(-1, null, "method not found: $method")
        }
    }

    private fun getLocation(callback: (Int, Any?, String?) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { callback(-1, null, "location permission not granted"); return }
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            val loc = lm.getLastKnownLocation(provider)
            if (loc != null) {
                callback(0, JSONObject().apply {
                    put("latitude", loc.latitude)
                    put("longitude", loc.longitude)
                    put("accuracy", loc.accuracy.toDouble())
                    put("provider", provider)
                }, null)
            } else callback(-1, null, "location unavailable")
        }.onFailure { callback(-1, null, it.message) }
    }
}
