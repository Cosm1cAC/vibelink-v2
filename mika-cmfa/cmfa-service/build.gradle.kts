plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.github.kr328.clash.service"
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
        java.srcDirs("../third_party/ClashMetaForAndroid/service/src/main/java")
        res.srcDirs("../third_party/ClashMetaForAndroid/service/src/main/res")
        manifest.srcFile("../third_party/ClashMetaForAndroid/service/src/main/AndroidManifest.xml")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":cmfa-core"))
    implementation(project(":cmfa-common"))

    ksp("com.github.kr328.kaidl:kaidl:1.15")
    ksp("androidx.room:room-compiler:2.4.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.room:room-runtime:2.4.2")
    implementation("androidx.room:room-ktx:2.4.2")
    implementation("com.github.kr328.kaidl:kaidl-runtime:1.15")
    implementation("dev.rikka.rikkax.preference:multiprocess:1.0.0")
}
