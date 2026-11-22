package com.zhangyuan.ridscanner

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var serialManager: UsbSerialManager

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
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        // 注入原生串口桥，替代 navigator.serial
        webView.addJavascriptInterface(SerialJsBridge(serialManager), "AndroidSerial")

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/www/rid_app.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    /** 把收到的一行 JSON 安全转义后注入页面全局回调。 */
    private fun injectLine(line: String) {
        val payload = JSONObject().put("line", line).toString()
        webView.post {
            webView.evaluateJavascript(
                "window.onNativeSerialLine && window.onNativeSerialLine($payload);",
                null
            )
        }
    }

    private fun injectState(connected: Boolean, msg: String) {
        val payload = JSONObject()
            .put("connected", connected)
            .put("message", msg)
            .toString()
        webView.post {
            webView.evaluateJavascript(
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
