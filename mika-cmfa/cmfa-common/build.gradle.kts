plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.github.kr328.clash.common"
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
    sourceSets["main"].apply {
        java.srcDirs("../third_party/ClashMetaForAndroid/common/src/main/java")
        res.srcDirs("../third_party/ClashMetaForAndroid/common/src/main/res")
        manifest.srcFile("../third_party/ClashMetaForAndroid/common/src/main/AndroidManifest.xml")
    }
}

dependencies {
    compileOnly(project(":cmfa-hideapi"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.8.0")
}

kotlin { jvmToolchain(17) }

