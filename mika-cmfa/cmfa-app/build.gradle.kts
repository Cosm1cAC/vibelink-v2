plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.github.kr328.clash"
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
        java.srcDirs("../third_party/ClashMetaForAndroid/app/src/main/java")
        res.srcDirs("../third_party/ClashMetaForAndroid/app/src/main/res")
        assets.srcDirs("../third_party/ClashMetaForAndroid/app/src/main/assets")
        manifest.srcFile("../third_party/ClashMetaForAndroid/app/src/main/AndroidManifest.xml")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly(project(":cmfa-hideapi"))

    implementation(project(":cmfa-core"))
    implementation(project(":cmfa-service"))
    implementation(project(":cmfa-design"))
    implementation(project(":cmfa-common"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.activity:activity:1.5.0")
    implementation("androidx.fragment:fragment:1.5.0")
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("com.google.android.material:material:1.6.1")
    implementation("io.github.g00fy2.quickie:quickie-bundled:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
