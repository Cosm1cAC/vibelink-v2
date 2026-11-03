plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxystack.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.proxystack.agent"
        minSdk = 26
        // 28：规避 Android 10+ W^X（后续阶段要执行 mihomo/tailscaled 二进制）
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}