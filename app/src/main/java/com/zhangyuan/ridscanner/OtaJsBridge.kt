package com.zhangyuan.ridscanner

import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 的 OTA 接口：window.AndroidOta.xxx()
 * - check()：检查更新，结果异步回 window.onNativeOtaCheck(json)
 * - downloadAndInstall(apkUrl)：下载并调起安装，进度回 window.onNativeOtaProgress(percent)
 */
class OtaJsBridge(
    private val updater: UpdateManager
) {
    @JavascriptInterface
    fun check(): String {
        updater.check()
        return "{\"ok\":true}"
    }

    @JavascriptInterface
    fun downloadAndInstall(apkUrl: String): String {
        updater.downloadAndInstall(apkUrl)
        return "{\"ok\":true}"
    }

    @JavascriptInterface
    fun isSupported(): Boolean = true
}
