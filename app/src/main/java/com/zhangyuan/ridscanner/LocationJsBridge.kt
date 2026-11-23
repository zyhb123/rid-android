package com.zhangyuan.ridscanner

import android.location.LocationListener
import android.location.LocationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    /**
     * 由 MainActivity 注入的权限请求动作（只有 Activity 能弹系统授权框）。
     * 可空注入而非改构造参数，保证旧测试 LocationJsBridge(context) 仍可编译。
     */
    @Volatile
    var permissionRequester: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否已获得精确定位权限 */
    @JavascriptInterface
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 网页主动请求定位权限（修复旧版“开启定位”按钮无权限时静默无反应）。
     * 切主线程调 Activity 的 requestPermissions；未注入回调则安全返回。
     */
    @JavascriptInterface
    fun requestPermission() {
        val req = permissionRequester ?: return
        mainHandler.post { req.invoke() }
    }

    /**
     * 主动催一次新鲜定位（系统 getLastKnownLocation 缓存可能是旧值或为空）。
     * 对每个可用 provider 请求单次更新，结果回写系统缓存，前端轮询即可取到。
     * 返回 {ok:true} 已发起 / {ok:false,reason}。
     */
    @JavascriptInterface
    fun requestSingleFix(): String {
        val res = JSONObject()
        if (!hasPermission()) {
            return res.put("ok", false).put("reason", "no_permission").toString()
        }
        if (lm == null) {
            return res.put("ok", false).put("reason", "unavailable").toString()
        }
        return try {
            val providers = lm.getProviders(true)
            if (providers.isEmpty()) {
                res.put("ok", false).put("reason", "no_provider").toString()
            } else {
                providers.forEach { p ->
                    try {
                        lm.requestSingleUpdate(p, object : LocationListener {
                            override fun onLocationChanged(location: android.location.Location) {}
                            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        }, mainHandler.looper)
                    } catch (ignore: Exception) {}
                }
                res.put("ok", true).put("providers", providers.joinToString(",")).toString()
            }
        } catch (e: Exception) {
            res.put("ok", false).put("reason", "exception:" + (e.message ?: "unknown")).toString()
        }
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
