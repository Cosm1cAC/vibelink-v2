plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.github.kr328.clash.hideapi"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDirs("../third_party/ClashMetaForAndroid/hideapi/src/main/java")
}
