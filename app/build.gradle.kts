import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.recoverylock.dpc"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.recoverylock.dpc"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        val buildTime = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        resValue("string", "build_time_display", "Build: $buildTime")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
