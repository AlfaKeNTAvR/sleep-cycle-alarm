// Android application module: Compose UI, depends on the pure-Kotlin :engine module for the sleep logic.
// No org.jetbrains.kotlin.android plugin: AGP 9+ has built-in Kotlin support (see root build.gradle.kts).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nikita.sleepcycle"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nikita.sleepcycle"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        // Exposes BuildConfig.DEBUG, the compile-time flag DebugOptions.kt/resolveDebugOptions and the Setup
        // screen's Debug row both gate on, so the debug/simulation entry point is compiled out of a release
        // build rather than merely hidden.
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.org.json)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
