package com.zhangyuan.ridscanner

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * 暴露给 WebView 的 JS 接口：window.AndroidSerial.xxx()
 * 网页原本调 navigator.serial，这里改调原生桥。
 *
 * 数据流：USB 收线 -> UsbSerialManager.onLine -> 注入 JS window.onNativeSerialLine(line)
 */
class SerialJsBridge(
    private val manager: UsbSerialManager
) {
    @JavascriptInterface
    fun isSupported(): Boolean = true

    @JavascriptInterface
    fun connect(): String {
        manager.connect()
        // 真正的连接结果通过状态回调 onNativeSerialState 异步推送
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun disconnect(): String {
        manager.disconnect("手动断开")
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun isConnected(): Boolean = manager.connected

    @JavascriptInterface
    fun deviceCount(): Int = manager.availableDrivers().size

    @JavascriptInterface
    fun diagnose(): String = manager.diagnose()
}
