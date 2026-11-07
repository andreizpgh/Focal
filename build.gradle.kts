plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here so the whole build, AGP's built-in Kotlin included, runs on the catalog's Kotlin.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
