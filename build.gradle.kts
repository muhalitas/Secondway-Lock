plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false

    // Fast JVM screenshot testing for XML/Compose without an emulator.
    id("app.cash.paparazzi") version "1.3.5" apply false

    // Google Play upload automation (GitHub Actions / CI).
    id("com.github.triplet.play") version "3.13.0" apply false
}
