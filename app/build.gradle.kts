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
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // 云端 debug 签名即可安装测试，免去自建 keystore；正式分发再上正式签名
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
