plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// نسخهٔ کتابخانهٔ Vosk؛ در صورت نیاز با -PvoskVersion=... عوض می‌شود
val voskVersion = (project.findProperty("voskVersion") as String?) ?: "0.3.75"
val jnaVersion = (project.findProperty("jnaVersion") as String?) ?: "5.18.1"

android {
    namespace = "ir.sadegh.assistant"
    compileSdk = 34
    defaultConfig {
        applicationId = "ir.sadegh.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.3"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("net.java.dev.jna:jna:$jnaVersion@aar")
    implementation("com.alphacephei:vosk-android:$voskVersion@aar")
}
