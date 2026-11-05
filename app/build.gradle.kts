import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 compiles Kotlin itself (built-in Kotlin); only the Compose compiler plugin is applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65_536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// The pinned speech runtime: sherpa-onnx 2.0.1 with ONNX Runtime 1.28.2. Verified before it is compiled in.
val speechRuntime = rootProject.file("build/dependencies/sherpa-onnx-2.0.1.aar")
require(speechRuntime.isFile) { "Missing the pinned speech runtime: ${speechRuntime.path}" }
check(sha256(speechRuntime) == "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7") {
    "Speech runtime checksum mismatch: ${speechRuntime.path}"
}

// Parakeet Ultra INT8 is packaged straight from this ignored directory; no extra 670 MB staging copy.
val ultraModelDirectory = rootProject.file("build/stt/parakeet-ultra-20261105/model")

android {
    namespace = "app.focal"
    // The pinned AndroidX set (Compose BOM 2026.09.00) requires compiling against API 37.
    // Runtime behaviour follows targetSdk, deliberately raised from 35 to 36 with this toolchain.
    compileSdk = 37
    defaultConfig {
        applicationId = "app.focal"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.0-alpha.9"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // The recognizer streams its model out of the APK once; keep the ONNX files stored.
    androidResources { noCompress += "onnx" }
    sourceSets.getByName("main").assets.directories.add(ultraModelDirectory.path)
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { abortOnError = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(files(speechRuntime))
    implementation(platform(libs.compose.bom))
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

val verifyUltraModel = tasks.register("verifyUltraModel") {
    group = "verification"
    description = "APKs must contain exactly the four pinned Parakeet Ultra files."
    val folder = ultraModelDirectory
    val expected = mapOf(
        "encoder.int8.onnx" to (652_282_526L to "7ef3ca4ffb49d60d4faa757b2930c8cec9927e73c24f6155d5b23a00229a7ed6"),
        "decoder.int8.onnx" to (11_845_274L to "1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922"),
        "joiner.int8.onnx" to (6_355_277L to "8ba94c6919c17a6bd27368fb89533628a72ffc81d4acedebf3cdcb2cae331dbb"),
        "tokens.txt" to (93_939L to "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )
    doLast {
        check(folder.isDirectory && !Files.isSymbolicLink(folder.toPath()) &&
            folder.listFiles()?.map { it.name }?.toSet() == expected.keys) {
            "The Ultra model directory must contain exactly the four pinned model files: ${folder.path}"
        }
        expected.forEach { (name, identity) ->
            val model = folder.resolve(name)
            check(model.isFile && !Files.isSymbolicLink(model.toPath()) && model.length() == identity.first) {
                "Missing or invalid Ultra file: $name"
            }
            check(sha256(model) == identity.second) { "Ultra file checksum mismatch: $name" }
        }
    }
}
tasks.matching { it.name in setOf("mergeDebugAssets", "mergeReleaseAssets") }.configureEach { dependsOn(verifyUltraModel) }
