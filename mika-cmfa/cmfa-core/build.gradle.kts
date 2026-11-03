plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.github.kr328.clash.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        resValue("string", "launch_name", "Clash")
        resValue("string", "application_name", "Clash")
        resValue("string", "release_name", "v2.11.36")
        resValue("integer", "release_code", "211036")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures.buildConfig = true
    // 直接复用 mika 已经打包的 mihomo 内核与 CMFA C 桥，不再重编 Go
    sourceSets["main"].apply {
        java.srcDirs("../third_party/ClashMetaForAndroid/core/src/main/java")
        jniLibs.srcDirs("../app/src/main/jniLibs")
        manifest.srcFile("../third_party/ClashMetaForAndroid/core/src/main/AndroidManifest.xml")
    }
}

dependencies {
    implementation(project(":cmfa-common"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.8.0")
}

kotlin { jvmToolchain(17) }

