plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zhangyuan.ridscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zhangyuan.ridscanner"
        // v7：versionCode 1->2，versionName 1.0.0->1.1.0。OTA 靠 versionCode 大小判断新旧。
        versionCode = 2
        versionName = "1.1.0"
        minSdk = 26
        targetSdk = 34
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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity.ktx)
    implementation(libs.constraintlayout)
}
