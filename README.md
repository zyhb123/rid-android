# RID 无人机监测 安卓 APP（RIDScanner）

手机 OTG 直连 ESP32-S3 RID 扫描板，本地读串口 JSON，高德地图实时显示无人机/飞手位置。
**零开放端口、零证书告警、数据不出手机。**

## 架构
- 原生 Kotlin 负责 USB 串口：`usb-serial-for-android`（CDC/CH340/CP210x/FTDI/PL2303，ESP32-S3 原生 CDC 直连）
- WebView 负责地图：复用已验证的 Leaflet + 高德瓦片 + WGS84→GCJ02 纠偏
- 桥接：`JavascriptInterface`（`window.AndroidSerial`）+ 两个全局回调 `onNativeSerialLine / onNativeSerialState`
- 串口参数：115200 8N1，按 `\n` 切行，逐行 JSON（`type=drone/boot/wifi_debug/ble_debug`）

## 目录
```
projects/rid-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── .github/workflows/build-rid-apk.yaml   # 云端自动编译
└── app/
    ├── build.gradle.kts / proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/www/rid_app.html        # 地图页面（串口层已换原生桥）
        ├── java/com/zhangyuan/ridscanner/
        │   ├── MainActivity.kt            # WebView 宿主 + JS 注入
        │   ├── UsbSerialManager.kt        # 原生 USB 串口
        │   └── SerialJsBridge.kt          # JS 接口
        └── res/                           # 布局/主题/图标/USB 过滤
```

## 出 APK（GitHub Actions 云端，不占本机磁盘）
1. 新建一个 **私有仓库**（GitHub 网页 New repository，Private）
2. 把本目录 `projects/rid-android/` 的**内容**推到仓库根目录
   （注意：workflow 在 `.github/workflows/`，要保持在仓库根的 `.github` 下）
3. push 后 Actions 标签页自动跑 `build-rid-apk`；也可手动 Run workflow
4. 跑绿后，进该次运行页底部 **Artifacts** 下载 `RIDScanner-debug-apk`，解压得 `app-debug.apk`
5. 传手机安装（首次需允许"未知来源"）

## 手机端使用
1. OTG 线接 ESP32-S3 板子（板子已上电、固件在跑）
2. 打开 APP → 点「🔌 连接串口」→ 系统弹 USB 授权，允许
3. 顶部状态变"已连接 …@115200"即成功，地图实时标点
4. 拔线自动断开；可在系统设置里把 APP 设为插入该 USB 设备时默认打开

## 调试 / 验证清单（首版上机必看）
- [ ] 点连接是否弹 USB 权限框（不弹=OTG 线/供电问题，或 device_filter 没命中，代码有动态枚举兜底）
- [ ] 授权后状态是否变"已连接"
- [ ] 面板 WiFi帧/BLE广播 计数是否增长（增长=串口通）
- [ ] 地图瓦片能否加载（需手机联网；高德 https 不受 cleartext 影响）
- [ ] 收到 drone 帧后是否出🛸标点、点击有弹窗

## 已知边界 / 后续可选
- 当前为 debug 签名（安装测试足够）；正式分发再上 release keystore
- 多串口设备默认连第一个；后续可加设备选择弹窗
- RSSI→距离粗估、S3+CSK6 语音播报、RTL-SDR 民航图层为待定增强，未含在本版
