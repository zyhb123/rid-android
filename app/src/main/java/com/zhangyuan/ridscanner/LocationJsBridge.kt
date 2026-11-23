package com.zhangyuan.ridscanner

import android.location.LocationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 暴露给 WebView 的定位接口：window.AndroidLocation.xxx()
 *
 * 用途：算"手机(观测者) -> 无人机 / 飞手"的距离。
 * 用系统 LocationManager，不依赖 Google Play 服务，鸿蒙兼容层可用。
 *
 * 数据流：网页请求定位 -> 取最近一次已知位置(GPS+网络择优) -> 返回 JSON。
 * 权限未授予时返回 {ok:false, reason:"no_permission"}，由前端引导授权。
 */
class LocationJsBridge(
    private val context: Context
) {
    private val lm: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** 是否已获得精确定位权限 */
    @JavascriptInterface
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 取最近一次已知位置。返回 JSON：
     * 成功 {ok:true, lat, lng, acc, src, t}
     * 失败 {ok:false, reason:"no_permission"|"unavailable"|"exception:..."}
     */
    @JavascriptInterface
    fun lastLocation(): String {
        val res = JSONObject()
        if (!hasPermission()) {
            return res.put("ok", false).put("reason", "no_permission").toString()
        }
        if (lm == null) {
            return res.put("ok", false).put("reason", "unavailable").toString()
        }
        try {
            val providers = lm.getProviders(true)
            var best: android.location.Location? = null
            for (p in providers) {
                @Suppress("MissingPermission")
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.accuracy < best!!.accuracy ||
                    loc.time > best!!.time
                ) {
                    best = loc
                }
            }
            val b = best
                ?: return res.put("ok", false).put("reason", "unavailable").toString()
            return res
                .put("ok", true)
                .put("lat", b.latitude)
                .put("lng", b.longitude)
                .put("acc", if (b.hasAccuracy()) b.accuracy else JSONObject.NULL)
                .put("src", b.provider)
                .put("t", b.time)
                .put("sdk", Build.VERSION.SDK_INT)
                .toString()
        } catch (e: Exception) {
            return res.put("ok", false)
                .put("reason", "exception:" + (e.message ?: "unknown")).toString()
        }
    }
}
