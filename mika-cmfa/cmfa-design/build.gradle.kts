plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.github.kr328.clash.design"
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
    buildFeatures.dataBinding = true
    buildFeatures.buildConfig = true
    sourceSets["main"].apply {
        java.srcDirs("../third_party/ClashMetaForAndroid/design/src/main/java")
        res.srcDirs("../third_party/ClashMetaForAndroid/design/src/main/res")
        manifest.srcFile("../third_party/ClashMetaForAndroid/design/src/main/AndroidManifest.xml")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":cmfa-common"))
    implementation(project(":cmfa-core"))
    implementation(project(":cmfa-service"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.activity:activity:1.5.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.fragment:fragment:1.5.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("com.google.android.material:material:1.6.1")
    implementation("com.google.zxing:core:3.5.3")
}
