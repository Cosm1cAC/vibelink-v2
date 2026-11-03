plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.31" apply false
    id("org.jetbrains.kotlin.kapt") version "2.1.20" apply false
}
