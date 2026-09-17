// Root build file. Declares plugin versions once so submodules can apply them without repeating a version.
//
// AGP 9+ has built-in Kotlin support: the :app module does not apply org.jetbrains.kotlin.android,
// only com.android.application (which bundles a Kotlin Gradle Plugin runtime dependency) plus the
// Compose compiler plugin. AGP 9.4.0 bundles KGP 2.2.10 by default; this buildscript block pins the
// Kotlin Gradle Plugin to the exact version in the catalog instead, so it matches :engine's plugin
// (org.jetbrains.kotlin.jvm) and the Compose compiler plugin, both version-locked to Kotlin itself.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
