import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nativeFaultVariant = providers.gradleProperty("focalNativeFaultTests").orNull
require(nativeFaultVariant == null || nativeFaultVariant in setOf("baseline", "patched")) {
    "focalNativeFaultTests must be baseline or patched; omit it for a normal build."
}

android {
    namespace = "app.focal"
    compileSdk = 35
    ndkVersion = "28.0.13004108"
    defaultConfig {
        applicationId = "app.focal"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0-alpha.3"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release",
                    "-DFOCAL_NATIVE_FAULT_TESTS=${if (nativeFaultVariant == null) "OFF" else "ON"}",
                    "-DFOCAL_NATIVE_FAULT_VARIANT=${nativeFaultVariant.orEmpty()}")
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources { noCompress += "bin" }
    buildTypes {
        debug {
            externalNativeBuild { cmake { arguments += "-DFOCAL_ANDROID_DEBUG_BUILD=ON" } }
        }
        release {
            externalNativeBuild { cmake { arguments += "-DFOCAL_ANDROID_DEBUG_BUILD=OFF" } }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    if (nativeFaultVariant != null) {
        sourceSets.getByName("debug").java.srcDir("src/nativeFault/java")
        sourceSets.getByName("androidTest").java.setSrcDirs(listOf(
            "src/nativeFaultTestSupport/java",
            if (nativeFaultVariant == "baseline") "src/nativeFaultBaselineTest/java" else "src/nativeFaultTest/java"))
    }
    lint { abortOnError = true }
}

gradle.taskGraph.whenReady {
    // Native optimization is Release for both Android variants in this project.
    val releaseTasks = allTasks.filter {
        it.project.path == project.path && it.name.contains("release", ignoreCase = true) &&
            !it.name.startsWith("configureCMakeRelease[") && !it.name.startsWith("buildCMakeRelease[")
    }
    check(nativeFaultVariant == null || releaseTasks.isEmpty()) {
        "Native fault tests are debug-only. Omit focalNativeFaultTests for ${releaseTasks.joinToString { it.path }}."
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyBundledModel by tasks.registering {
    group = "verification"
    description = "Release APKs must contain the verified offline speech model."
    doLast {
        val model = file("src/main/assets/ggml-base-q5_1.bin")
        val provenance = file("src/main/assets/model-provenance.json")
        check(model.isFile && provenance.isFile) {
            "Missing speech model. Run python3 scripts/fetch-model.py before making a release."
        }
        val metadata = JsonSlurper().parse(provenance) as Map<*, *>
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == metadata["sha256"]) { "Bundled model checksum mismatch; run the verified downloader." }
    }
}
tasks.matching { it.name in setOf("packageRelease", "bundleRelease") }.configureEach { dependsOn(verifyBundledModel) }
