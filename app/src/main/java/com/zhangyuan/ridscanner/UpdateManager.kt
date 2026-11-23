package com.zhangyuan.ridscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * OTA 升级管理：检查 GitHub Releases 上的 update.json -> 比对 versionCode ->
 * 下载 APK 到外部私有目录 -> 经 FileProvider 调起系统安装器（覆盖安装，不丢数据）。
 *
 * 全部回调切到主线程，再由 JsBridge 注入网页 window.onNativeOtaXxx()。
 * 零自建服务器、零成本；清单与 APK 均托管在 GitHub Releases。
 */
class UpdateManager(
    private val activity: Activity,
    private val webView: android.webkit.WebView
) {
    companion object {
        // 固定清单地址：GitHub Release 附件的固定下载地址（发布时上传 update.json）
        // 走 redirect 友好的静态地址；如启用 Pages 可替换。
        private const val MANIFEST_URL =
            "https://github.com/zyhb123/rid-android/releases/latest/download/update.json"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var downloading = false

    private fun currentVersionCode(): Long {
        return try {
            val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
            else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        } catch (e: Exception) { 0L }
    }

    private fun currentVersionName(): String {
        return try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
    }

    /** 注入 JS 回调，payload 为 JSON 字符串。 */
    private fun emit(fn: String, json: String) {
        main.post {
            if (!activity.isFinishing) {
                webView.evaluateJavascript("try{window.$fn&&window.$fn($json);}catch(e){}", null)
            }
        }
    }

    private fun httpGet(url: String, followRedirect: Boolean = true): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = followRedirect
        conn.setRequestProperty("User-Agent", "RIDScanner-OTA/${currentVersionName()}")
        return conn
    }

    /** GitHub release 下载会 302 到 objects 存储，需手动跟随到最终地址。 */
    private fun resolveRedirect(url: String, maxHop: Int = 5): String {
        var u = url
        repeat(maxHop) {
            val conn = httpGet(u, false)
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

    /** 检查更新。结果通过 window.onNativeOtaCheck(json) 回传网页。 */
    fun check() {
        io.execute {
            try {
                val resolved = resolveRedirect(MANIFEST_URL)
                val conn = httpGet(resolved)
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val manifest = JSONObject(text)
                val latestCode = manifest.optLong("versionCode", 0L)
                val localCode = currentVersionCode()
                val hasUpdate = latestCode > localCode
                val out = JSONObject()
                    .put("ok", true)
                    .put("hasUpdate", hasUpdate)
                    .put("latestVersion", manifest.optString("versionName", ""))
                    .put("currentVersion", currentVersionName())
                    .put("latestCode", latestCode)
                    .put("currentCode", localCode)
                    .put("releaseNotes", manifest.optString("releaseNotes", ""))
                    .put("apkUrl", manifest.optString("apkUrl", ""))
                    .put("apkSize", manifest.optLong("apkSize", 0L))
                emit("onNativeOtaCheck", out.toString())
            } catch (e: Exception) {
                val out = JSONObject()
                    .put("ok", false)
                    .put("error", (e.message ?: "检查更新失败"))
                emit("onNativeOtaCheck", out.toString())
            }
        }
    }

    /** 下载并调起安装。progress 经 window.onNativeOtaProgress(percent) 回传。 */
    fun downloadAndInstall(apkUrl: String) {
        if (downloading) return
        if (apkUrl.isBlank()) {
            emit("onNativeOtaError", JSONObject().put("error", "下载地址为空").toString())
            return
        }
        downloading = true
        io.execute {
            var tmp: File? = null
            try {
                val finalUrl = resolveRedirect(apkUrl)
                val conn = httpGet(finalUrl)
                val total = conn.contentLengthLong.coerceAtLeast(0L)
                val dir = File(activity.getExternalFilesDir(null), "update").apply { mkdirs() }
                // 清理旧包
                dir.listFiles()?.forEach { runCatching { it.delete() } }
                val outFile = File(dir, "rid_update.apk")
                tmp = outFile
                var readAll = 0L
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            readAll += n
                            if (total > 0) {
                                val pct = ((readAll * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    emit("onNativeOtaProgress", JSONObject().put("percent", pct).toString())
                                }
                            }
                        }
                        output.flush()
                    }
                }
                conn.disconnect()
                emit("onNativeOtaProgress", JSONObject().put("percent", 100).toString())
                installApk(outFile)
            } catch (e: Exception)
            {
                emit("onNativeOtaError", JSONObject().put("error", (e.message ?: "下载失败")).toString())
            } finally {
                downloading = false
            }
        }
    }

    /** 经 FileProvider 授予系统安装器临时读权限并调起安装界面。 */
    private fun installApk(file: File) {
        main.post {
            try {
                val authority = "${activity.packageName}.fileprovider"
                val uri: Uri = FileProvider.getUriForFile(activity, authority, file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                emit("onNativeOtaInstall", JSONObject().put("ok", true).toString())
            } catch (e: Exception) {
                emit("onNativeOtaError", JSONObject().put("error", (e.message ?: "无法调起安装器")).toString())
            }
        }
    }
}
