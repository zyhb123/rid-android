package com.zhangyuan.ridscanner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * 原生 USB 串口管理：替代浏览器 Web Serial。
 * 走安卓 USB Host API + usb-serial-for-android，
 * 兼容 ESP32-S3 原生 CDC、CH340、CP210x、FTDI、PL2303。
 *
 * 读到的数据按 '\n' 切行，通过 onLine 回调回传（已在主线程）。
 */
class UsbSerialManager(
    private val context: Context,
    private val onLine: (String) -> Unit,
    private val onState: (connected: Boolean, message: String) -> Unit
) {
    companion object {
        private const val TAG = "UsbSerial"
        private const val BAUD = 115200
        private const val ACTION_USB_PERMISSION = "com.zhangyuan.ridscanner.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var port: UsbSerialPort? = null
    private var driver: UsbSerialDriver? = null
    private var ioManager: SerialInputOutputManager? = null
    private val lineBuffer = StringBuilder()

    @Volatile var connected = false
        private set

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (granted && device != null) {
                    openDevice(device)
                } else {
                    onState(false, "未授予 USB 权限")
                }
            } else if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                disconnect("设备已拔出")
            }
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(usbReceiver) }
    }

    /** 返回当前可用的串口设备名列表（供 JS 侧展示/选择，这里默认连第一个）。 */
    fun availableDrivers(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    /** 发起连接：有权限直接开，没权限先申请。 */
    fun connect() {
        val drivers = availableDrivers()
        if (drivers.isEmpty()) {
            onState(false, "没发现 USB 串口设备，确认 OTG 已接、板子已上电")
            return
        }
        val drv = drivers.first()
        driver = drv
        val device = drv.device
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
            )
            usbManager.requestPermission(device, intent)
            return
        }
        openDevice(device)
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val drv = driver ?: availableDrivers().firstOrNull { it.device.deviceId == device.deviceId }
            ?: run { onState(false, "找不到驱动"); return }
            val connection = usbManager.openDevice(device)
                ?: run { onState(false, "打开 USB 设备失败"); return }
            val p = drv.ports.firstOrNull()
                ?: run { onState(false, "设备没有可用串口端口"); return }
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // ESP32-S3 CDC 流控关掉，DTR/RTS 拉高确保芯片运行
            runCatching { p.setDTR(true); p.setRTS(true) }
            port = p

            val mgr = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    handleData(data)
                }
                override fun onRunError(e: Exception) {
                    Log.e(TAG, "IO error", e)
                    mainHandler.post { disconnect("串口异常：${e.message}") }
                }
            })
            ioManager = mgr
            ioExecutor.submit(mgr)
            connected = true
            onState(true, "已连接 ${device.deviceName} @${BAUD}")
        } catch (e: Exception) {
            Log.e(TAG, "openDevice failed", e)
            onState(false, "打开失败：${e.message}")
            runCatching { port?.close() }
            port = null
            connected = false
        }
    }

    private fun handleData(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        val lines = ArrayList<String>()
        synchronized(lineBuffer) {
            lineBuffer.append(text)
            var idx = lineBuffer.indexOf('\n')
            while (idx >= 0) {
                val line = lineBuffer.substring(0, idx).trim()
                lineBuffer.delete(0, idx + 1)
                if (line.isNotEmpty()) lines.add(line)
                idx = lineBuffer.indexOf('\n')
            }
        }
        // 切回主线程喂给 WebView
        mainHandler.post {
            for (l in lines) {
                if (l.startsWith("{")) onLine(l)
            }
        }
    }

    fun disconnect(reason: String = "已断开") {
        connected = false
        runCatching { ioManager?.stop() }
        runCatching { port?.close() }
        port = null
        synchronized(lineBuffer) { lineBuffer.setLength(0) }
        mainHandler.post { onState(false, reason) }
    }
}
