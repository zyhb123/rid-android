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
 * v2 加固：
 * - 打开/参数设置移到 IO 线程，杜绝主线程 USB 阻塞导致 ANR/闪退
 * - 任何失败都把原因回传 JS 面板（不只 toast），按钮状态可复位
 * - connect 防重入；数据行 trim 后再判 JSON，容忍前导空白
 * - 插入设备且已有权限时自动连接
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

    // 防重入：正在请求权限/打开过程中忽略重复点击
    @Volatile private var connecting = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    connecting = false
                    if (granted && device != null) {
                        openDevice(device)
                    } else {
                        postState(false, "未授予 USB 权限：请在弹窗里点“确定/允许”")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB 设备插入，尝试自动连接")
                    val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (dev != null && !connected && !connecting) {
                        if (usbManager.hasPermission(dev)) openDevice(dev) else connect()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB 设备拔出")
                    if (connected) disconnect("设备已拔出")
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregister() = runCatching { context.unregisterReceiver(usbReceiver) }

    fun availableDrivers(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    /** 由网页按钮调用：枚举 -> 有权限直接开，没权限申请 */
    fun connect() {
        if (connected) {
            postState(true, "已连接，无需重复操作")
            return
        }
        if (connecting) {
            Log.i(TAG, "正在连接中，忽略重复点击")
            return
        }
        val drivers = availableDrivers()
        if (drivers.isEmpty()) {
            // 关键修复：无设备也要明确回传面板，不能只 toast
            postState(false, "未发现串口设备：请确认 OTG 已插牢、扫描板已上电")
            return
        }
        val first = drivers.first()
        val device = first.device
        if (!usbManager.hasPermission(device)) {
            connecting = true
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, pi)
            postState(false, "请在手机弹窗里允许 USB 权限…")
            return
        }
        openDevice(device)
    }

    /** 在 IO 线程打开设备并配置串口，避免主线程阻塞 ANR */
    private fun openDevice(device: UsbDevice) {
        connecting = true
        postState(false, "正在打开串口…")
        ioExecutor.execute {
            try {
                val drv = availableDrivers().firstOrNull { it.device.deviceId == device.deviceId }
                    ?: UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: run {
                        postState(false, "找不到该设备的串口驱动")
                        connecting = false
                        return@execute
                    }
                val p = drv.ports.firstOrNull()
                if (p == null) {
                    postState(false, "该设备没有可用串口端口")
                    connecting = false
                    return@execute
                }

                val conn = usbManager.openDevice(device)
                if (conn == null) {
                    postState(false, "打开 USB 连接失败：可能被其它应用占用，请重插后再试")
                    connecting = false
                    return@execute
                }
                p.open(conn)
                p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                val mgr = SerialInputOutputManager(p) { data -> handleData(data) }
                mgr.readTimeout = 0
                ioExecutor.execute(mgr)

                driver = drv
                port = p
                ioManager = mgr
                connected = true
                connecting = false
                Log.i(TAG, "串口已连接 baud=$BAUD vid=${device.vendorId} pid=${device.productId}")
                postState(true, "已连接 ${drv.javaClass.simpleName} @${BAUD}bps")
            } catch (e: Exception) {
                Log.e(TAG, "openDevice 失败", e)
                runCatching { port?.close() }
                port = null
                connected = false
                connecting = false
                postState(false, "打开串口失败：${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }
    }

    private fun postState(ok: Boolean, msg: String) {
        mainHandler.post { onState(ok, msg) }
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
        mainHandler.post {
            for (l in lines) {
                // 容错：去空白后是 JSON 对象才喂给协议解析，其余忽略但不丢日志线索
                if (l.startsWith("{")) onLine(l)
                else Log.d(TAG, "忽略非JSON行: $l")
            }
        }
    }

    fun disconnect(reason: String = "已断开") {
        connecting = false
        connected = false
        runCatching { ioManager?.stop() }
        runCatching { port?.close() }
        port = null
        synchronized(lineBuffer) { lineBuffer.setLength(0) }
        postState(false, reason)
    }
}
