package com.zhangyuan.ridscanner

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var serialManager: UsbSerialManager
    private lateinit var updateManager: UpdateManager

    @Volatile private var destroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        serialManager = UsbSerialManager(
            context = this,
            onLine = { line -> injectLine(line) },
            onState = { connected, msg -> injectState(connected, msg) }
        )
        serialManager.register()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 启动时若扫描板已插且已授权，自动连接，省一次点击
                autoConnectIfReady()
            }
        }
        webView.webChromeClient = WebChromeClient()
        // 注入原生串口桥，替代 navigator.serial
        webView.addJavascriptInterface(SerialJsBridge(serialManager), "AndroidSerial")
        // 注入定位桥：手机坐标，用于算“手机→无人机/飞手”距离
        LocationJsBridge(this).also { bridge ->
            // 让网页按钮能主动弹系统授权框（修复无权限时点“开启定位”无反应）
            bridge.permissionRequester = { ensureLocationPermission() }
            webView.addJavascriptInterface(bridge, "AndroidLocation")
        }
        // 注入 OTA 桥：应用内检查更新/下载/覆盖安装
        updateManager = UpdateManager(this, webView)
        webView.addJavascriptInterface(OtaJsBridge(updateManager), "AndroidOta")

        // 运行时定位权限（targetSdk 34 必须动态申请）
        ensureLocationPermission()

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/www/rid_app.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    /** 没有定位权限就弹系统授权框；已授权则不动。 */
    private fun ensureLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                RC_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_LOCATION) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && !destroyed) {
                // 授权通过：主动通知网页立刻自动定位并飞地图（修复原先回调为空、只能手动点按钮的问题）
                webView.post {
                    webView.evaluateJavascript(
                        "try{window.onNativeLocationGranted&&window.onNativeLocationGranted();}catch(e){}",
                        null
                    )
                }
            }
        }
    }

    companion object {
        private const val RC_LOCATION = 1001
    }

    /** 页面就绪后：已有已授权串口设备则自动连，未授权则不动（等用户点按钮）。 */
    private fun autoConnectIfReady() {
        try {
            val drivers = serialManager.availableDrivers()
            if (drivers.isEmpty()) return
            val device = drivers.first().device
            val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
            if (usbManager.hasPermission(device) && !serialManager.connected) {
                serialManager.connect()
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "autoConnect skip: ${e.message}")
        }
    }

    /** 把收到的一行 JSON 安全转义后注入页面全局回调。 */
    private fun injectLine(line: String) {
        if (destroyed) return
        val payload = JSONObject().put("line", line).toString()
        webView.post {
            if (!destroyed) webView.evaluateJavascript(
                "window.onNativeSerialLine && window.onNativeSerialLine($payload);",
                null
            )
        }
    }

    private fun injectState(connected: Boolean, msg: String) {
        if (destroyed) return
        val payload = JSONObject()
            .put("connected", connected)
            .put("message", msg)
            .toString()
        webView.post {
            if (!destroyed) webView.evaluateJavascript(
                "window.onNativeSerialState && window.onNativeSerialState($payload);",
                null
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        serialManager.unregister()
        serialManager.disconnect("页面销毁")
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
