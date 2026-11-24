package com.zhangyuan.ridscanner

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 空域数据热更管理：从 GitHub Releases 拉取权威空域 GeoJSON（airspace.json），
 * 经主线程注入网页 window.onNativeAirspace(json)。
 *
 * 设计原则（与 UpdateManager 同构）：
 * - 零自建服务器，复用现有 GitHub Releases 托管，跟 update.json 并排发布。
 * - 只负责"换数据"，不内置任何空域边界；权威边界以 UOM 平台发布为准，由人工整理入库。
 * - 拉取失败/超时/无网络时静默回调错误，前端继续用 APK 内置 rid_airspace.js 兜底，
 *   绝不阻断主监测功能。
 *
 * airspace.json 约定：
 * {
 *   "schema": "rid-airspace/v1",
 *   "updatedAt": "2026-11-24",
 *   "source": "UOM ...",
 *   "features": [ ...GeoJSON Feature(no_fly/restricted)... ]  // 坐标 WGS-84
 * }
 */
class AirspaceManager(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        // 与 OTA 清单同目录，发布 Release 时一并上传 airspace.json
        private const val AIRSPACE_URL =
            "https://github.com/zyhb123/rid-android/releases/latest/download/airspace.json"
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 15000
        private const val MAX_REDIRECT = 5
        // 简单上限，防止异常超大响应拖垮 WebView（正常空域数据几十 KB 内）
        private const val MAX_BYTES = 2 * 1024 * 1024
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun emit(fn: String, json: String) {
        main.post {
            if (!activity.isFinishing) {
                webView.evaluateJavascript("try{window.$fn&&window.$fn($json);}catch(e){}", null)
            }
        }
    }

    private fun versionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    private fun open(url: String, followRedirect: Boolean): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = followRedirect
        conn.setRequestProperty("User-Agent", "RIDScanner-Airspace/${versionName()}")
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    /** GitHub release 下载会 302 到 objects 存储，手动跟随到最终地址。 */
    private fun resolveRedirect(url: String): String {
        var u = url
        repeat(MAX_REDIRECT) {
            val conn = open(u, false)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return u
                    u = loc
                } else {
                    return u
                }
            } finally {
                conn.disconnect()
            }
        }
        return u
    }

    /**
     * 拉取空域数据。结果回 window.onNativeAirspace(json)，
     * 任何失败回 window.onNativeAirspaceError({error})，均不抛异常到调用方。
     */
    fun fetch() {
        io.execute {
            try {
                val resolved = resolveRedirect(AIRSPACE_URL)
                val conn = open(resolved, true)
                try {
                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        emitError("HTTP $code")
                        return@execute
                    }
                    val raw = conn.inputStream.use { stream ->
                        val buf = ByteArray(4096)
                        val out = java.io.ByteArrayOutputStream()
                        var total = 0
                        while (true) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_BYTES) {
                                emitError("响应超过 2MB 上限")
                                return@execute
                            }
                            out.write(buf, 0, n)
                        }
                        out.toString("UTF-8")
                    }
                    // 校验是合法 JSON 且含 features 数组，避免脏数据进渲染层
                    val parsed = JSONObject(raw)
                    val features = parsed.optJSONArray("features")
                    if (features == null) {
                        emitError("数据缺少 features 字段")
                        return@execute
                    }
                    // 原样回传给前端（前端已有 L.geoJSON 渲染 + WGS84→GCJ02 转换）
                    emit("onNativeAirspace", JSONObject.quote(raw))
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // 离线/超时/解析失败都走这里：前端静默回退内置数据，不打扰用户
                emitError(e.message ?: "空域数据拉取失败")
            }
        }
    }

    private fun emitError(msg: String) {
        emit("onNativeAirspaceError", JSONObject().put("error", msg).toString())
    }
}
