package com.zhangyuan.ridscanner

import android.webkit.JavascriptInterface
import org.json.JSONArray
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

    /**
     * 返回匹配到的串口设备明细 JSON 数组字符串：
     * [{driver, ports, vid, pid, permission}]，供前端诊断弹窗逐行展示。
     * 前端不要再调不存在的 availableDrivers()（旧版报错 is not a function）。
     */
    @JavascriptInterface
    fun driversInfo(): String {
        val arr = JSONArray()
        for (d in manager.availableDrivers()) {
            val dev = d.device
            val o = JSONObject()
            o.put("driver", d.javaClass.simpleName)
            o.put("ports", d.ports.size)
            o.put("vid", String.format("0x%04X", dev.vendorId))
            o.put("pid", String.format("0x%04X", dev.productId))
            o.put("permission", manager.hasPermission(dev))
            arr.put(o)
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun diagnose(): String = manager.diagnose()
}
