import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
    id("com.github.triplet.play")

    // Firebase/Google Sign-In: only apply google-services plugin when a google-services.json is present.
    // This keeps CI + public repo builds working without committing secrets.
    id("com.google.gms.google-services") apply false
}

// Apply google-services if config exists (either per-variant or at module root).
if (file("google-services.json").exists() ||
    file("src/debug/google-services.json").exists() ||
    file("src/release/google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

fun propOrEnv(name: String): String? =
    (providers.gradleProperty(name).orNull ?: System.getenv(name))?.takeIf { it.isNotBlank() }

fun existingFilePropOrEnv(name: String): File? {
    val p = propOrEnv(name) ?: return null
    val f = file(p)
    return if (f.exists()) f else null
}

android {
    namespace = "app.secondway.lock"
    compileSdk = 34
    defaultConfig {
        // Keep the app id aligned with google-services.json (Firebase).
        applicationId = "com.secondwaybrowser.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "1.0.11"
        val buildTime = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        resValue("string", "build_time_display", "Build: $buildTime")
        resValue("string", "build_time_value", buildTime)
    }

    val releaseKeystoreFile = existingFilePropOrEnv("SIGNING_STORE_FILE")
    val releaseStorePassword = propOrEnv("SIGNING_STORE_PASSWORD")
    val releaseKeyAlias = propOrEnv("SIGNING_KEY_ALIAS")
    val releaseKeyPassword = propOrEnv("SIGNING_KEY_PASSWORD")
    val hasReleaseSigning =
        releaseKeystoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

    signingConfigs {
        create("release") {
            // If signing is not configured (e.g. local dev), keep release builds unsigned.
            if (hasReleaseSigning) {
                storeFile = releaseKeystoreFile
                this.storePassword = releaseStorePassword
                this.keyAlias = releaseKeyAlias
                this.keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

play {
    // CI should write a JSON file and pass its path via PLAY_SERVICE_ACCOUNT_JSON_FILE.
    existingFilePropOrEnv("PLAY_SERVICE_ACCOUNT_JSON_FILE")?.let { serviceAccountCredentials.set(it) }
    track.set(propOrEnv("PLAY_TRACK") ?: "internal")
    defaultToAppBundles.set(true)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Browser module dependencies (merged from SafeBrowser).
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Firebase allowlist sync (Google Sign-In + Firestore).
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Paparazzi (JVM snapshot tests)
    testImplementation("junit:junit:4.13.2")
}
