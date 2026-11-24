package com.zhangyuan.ridscanner

import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 的空域接口：window.AndroidAirspace.xxx()
 * - fetch()：主动拉取云端权威空域 GeoJSON，结果异步回 window.onNativeAirspace(json)
 * - isSupported()：能力探测，老桥缺失时前端自动回退内置 rid_airspace.js
 */
class AirspaceJsBridge(
    private val airspaceManager: AirspaceManager
) {
    @JavascriptInterface
    fun fetch(): String {
        airspaceManager.fetch()
        return "{\"ok\":true}"
    }

    @JavascriptInterface
    fun isSupported(): Boolean = true
}
