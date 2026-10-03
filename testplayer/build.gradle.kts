// Test-only Android app: a stand-in for Spotify or an audiobook player on the emulator, so the bedtime fade and
// the pause on sleep can be exercised without a phone. Never shipped; see the README's "Test on the emulator".
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nikita.testplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nikita.testplayer"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

kotlin {
    jvmToolchain(21)
}
