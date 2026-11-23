plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zhangyuan.ridscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zhangyuan.ridscanner"
        minSdk = 26          // Android 8.0；鸿蒙 4.2 兼容层足够
        targetSdk = 34
        // OTA 靠 versionCode 大小判断新旧。v6=1(随机debug签名)；1.1.0=2；1.2.0=3(回中按钮)；1.2.1=4(高德风蓝点定位+定位按钮真正可用)。
        versionCode = 4
        versionName = "1.2.1"
    }

    // 固定签名：CI 从 GitHub Secrets 解码 keystore 并经环境变量注入。
    // 本地若无这些环境变量则跳过固定签名（仍可出 debug 包），CI 一定具备，保证所有正式包签名一致。
    val keystoreFilePath = System.getenv("RID_KEYSTORE_FILE")
    val hasReleaseSigning = keystoreFilePath != null && file(keystoreFilePath).exists()

    signingConfigs {
        if (hasReleaseSigning) {
            create("ridRelease") {
                storeFile = file(keystoreFilePath!!)
                storePassword = System.getenv("RID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RID_KEYSTORE_ALIAS")
                keyPassword = System.getenv("RID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // CI 中 debug 也用固定签名，使任意渠道产出的包都能被后续 OTA 覆盖安装
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("ridRelease")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("ridRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // 原生 USB 串口库：支持 CDC(ESP32-S3)、CH340、CP210x、FTDI、PL2303
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
}
