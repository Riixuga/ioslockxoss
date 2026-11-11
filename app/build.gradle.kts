plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.miko.ioslock"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.miko.ioslock"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "0.41-ease"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
