import groovy.json.JsonSlurper
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

// models/*.json pin the speech runtime and model. scripts/fetch-model.py reads the same files,
// so paths, sizes and digests live in one place.
data class Pinned(val name: String, val bytes: Long, val sha256: String)

@Suppress("UNCHECKED_CAST")
fun lock(name: String) = JsonSlurper().parse(rootProject.file("models/$name.json")) as Map<String, Any>
val runtimeLock = lock("sherpa-onnx")
val modelLock = lock("parakeet-ultra")
@Suppress("UNCHECKED_CAST")
val modelFiles = (modelLock.getValue("files") as List<Map<String, Any>>).map {
    Pinned(it.getValue("name") as String, (it.getValue("bytes") as Number).toLong(), it.getValue("sha256") as String)
}

// sherpa-onnx: every build links Focal's JNI bridge against the C API library inside this AAR,
// so the AAR is verified first. Nothing else from it (Kotlin API, JNI library) is used.
val speechRuntime = rootProject.file(runtimeLock.getValue("path") as String)
require(speechRuntime.isFile) {
    "Missing the speech runtime ${speechRuntime.path}. Run: python3 scripts/fetch-model.py --runtime-only"
}
check(speechRuntime.length() == (runtimeLock.getValue("bytes") as Number).toLong() &&
    sha256(speechRuntime) == runtimeLock.getValue("sha256")) {
    "The speech runtime ${speechRuntime.path} does not match models/sherpa-onnx.json."
}
// The bridge compiles against the C API header of the same sherpa-onnx commit, vendored unchanged.
@Suppress("UNCHECKED_CAST")
val cApiHeader = (runtimeLock.getValue("c_api_header") as Map<String, Any>).let {
    rootProject.file(it.getValue("path") as String) to it.getValue("sha256") as String
}
check(cApiHeader.first.isFile && sha256(cApiHeader.first) == cApiHeader.second) {
    "${cApiHeader.first.path} is not the sherpa-onnx C API header pinned in models/sherpa-onnx.json."
}
// The ABIs Focal ships (release: arm64-v8a only) and the two prebuilt libraries the bridge needs.
val speechRuntimeAbis = listOf("arm64-v8a", "x86_64")
val speechRuntimeLibraries = listOf("libsherpa-onnx-c-api.so", "libonnxruntime.so")
val speechRuntimeDirectory = layout.buildDirectory.dir("speech-runtime").get().asFile
val extractSpeechRuntime = tasks.register<Sync>("extractSpeechRuntime") {
    group = "build"
    description = "Unpacks the sherpa-onnx C API and ONNX Runtime libraries from the verified AAR for CMake."
    from(zipTree(speechRuntime)) {
        include(speechRuntimeAbis.flatMap { abi -> speechRuntimeLibraries.map { "jni/$abi/$it" } })
        eachFile { path = path.removePrefix("jni/") }
        includeEmptyDirs = false
    }
    into(speechRuntimeDirectory)
}

// Parakeet Ultra INT8 is packaged straight from this ignored directory; no extra 670 MB staging copy.
// Release builds always need it. Debug, unit-test and lint builds work without it, so checks can run
// without the download; such an APK records but says it cannot transcribe.
val ultraModelDirectory = rootProject.file(modelLock.getValue("directory") as String)
// Any locked file means a model is meant to be packaged, and verifyUltraModel then requires all of them.
// Stray files alone (.DS_Store, an interrupted copy) leave a model-less debug build working.
val modelPresent = modelFiles.any { ultraModelDirectory.resolve(it.name).exists() }
val requireModel = when (val value = providers.gradleProperty("focal.requireModel").orNull) {
    null, "false" -> false
    "true" -> true
    else -> error("focal.requireModel must be true or false, not $value")
}
if (!modelPresent) logger.lifecycle(
    "Speech model not found in ${ultraModelDirectory.path}: debug APKs will record but not transcribe. " +
        "Run python3 scripts/fetch-model.py for a complete build."
)

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
        // The app verifies its installed model copy against the same lock the build used.
        buildConfigField("String", "ULTRA_MODEL_FILES",
            "\"" + modelFiles.joinToString(";") { "${it.name}:${it.bytes}:${it.sha256}" } + "\"")
        buildConfigField("String", "SPEECH_RUNTIME_VERSION", "\"${runtimeLock.getValue("version")}\"")
        buildConfigField("String", "SPEECH_RUNTIME_REVISION", "\"${runtimeLock.getValue("revision")}\"")
        buildConfigField("String", "ONNXRUNTIME_VERSION", "\"${runtimeLock.getValue("onnxruntime")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake { arguments += "-DFOCAL_SPEECH_RUNTIME_DIR=${speechRuntimeDirectory.path}" }
        }
    }
    // The JNI bridge (src/main/cpp) is Focal's only native code.
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // The recognizer streams its model out of the APK once; keep the ONNX files stored.
    androidResources { noCompress += "onnx" }
    // Ship the verified sherpa-onnx and ONNX Runtime binaries byte for byte; only the bridge is stripped.
    packaging { jniLibs { keepDebugSymbols += speechRuntimeLibraries.map { "**/$it" } } }
    if (modelPresent) sourceSets.getByName("main").assets.directories.add(ultraModelDirectory.path)
    // ABI filters are set per build type only: AGP unions them with defaultConfig's.
    buildTypes {
        // x86_64 serves the emulator for debug builds and device tests.
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            // Phones are arm64; the release APK carries no x86_64 runtime libraries.
            ndk { abiFilters += "arm64-v8a" }
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
    implementation(platform(libs.compose.bom))
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.core.splashscreen)
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
    description = "The packaged model directory must hold exactly the files in models/parakeet-ultra.json."
    val folder = ultraModelDirectory
    val expected = modelFiles
    val stamp = layout.buildDirectory.file("verified-model/parakeet-ultra.txt")
    inputs.files(fileTree(folder)).withPropertyName("model")
    inputs.property("lock", expected.toString())
    outputs.file(stamp)
    doLast {
        check(folder.isDirectory && !Files.isSymbolicLink(folder.toPath())) {
            "Missing speech model in ${folder.path}. Release builds (and -Pfocal.requireModel=true) need it. " +
                "Run: python3 scripts/fetch-model.py"
        }
        val fix = "Run: python3 scripts/fetch-model.py"
        val found = folder.list().orEmpty().toSet()
        val names = expected.map { it.name }.toSet()
        check(found == names) {
            "${folder.path} must hold exactly the files in models/parakeet-ultra.json" +
                (names - found).sorted().let { if (it.isEmpty()) "" else "; missing: ${it.joinToString()}" } +
                (found - names).sorted().let { if (it.isEmpty()) "" else "; unexpected: ${it.joinToString()}" } +
                ". Remove unexpected files and run: python3 scripts/fetch-model.py"
        }
        expected.forEach { file ->
            val model = folder.resolve(file.name)
            check(model.isFile && !Files.isSymbolicLink(model.toPath()) && model.length() == file.bytes) {
                "Missing or invalid model file: ${model.path}. $fix"
            }
            check(sha256(model) == file.sha256) { "Model file checksum mismatch: ${model.path}. $fix" }
        }
        stamp.get().asFile.apply { parentFile.mkdirs(); writeText(expected.joinToString("\n", postfix = "\n")) }
    }
}
// src/main/assets is AGP's default asset directory, and Whisper-era fetch scripts put model files
// there. They are ignored by Git, so only this check keeps them out of the APK.
val verifyAppAssets = tasks.register("verifyAppAssets") {
    group = "verification"
    description = "src/main/assets may hold only the bundled licence texts."
    val folder = file("src/main/assets")
    val shipped = setOf("licenses.txt", "ultra-licenses.txt")
    doLast {
        val unexpected = folder.list().orEmpty().filterNot { it in shipped }.sorted()
        check(unexpected.isEmpty()) {
            "${folder.path} holds files that must not ship: ${unexpected.joinToString()}. " +
                "Older Focal builds placed model files there; move them out of the project."
        }
    }
}
// Every packaged model is verified; release builds and -Pfocal.requireModel=true require one.
val verifiedAssetMerges = buildSet {
    add("mergeReleaseAssets")
    if (modelPresent || requireModel) add("mergeDebugAssets")
}
tasks.matching { it.name in verifiedAssetMerges }.configureEach { dependsOn(verifyUltraModel) }
tasks.matching { it.name in setOf("mergeDebugAssets", "mergeReleaseAssets") }.configureEach { dependsOn(verifyAppAssets) }

// CMake links the bridge against the extracted runtime, so it must exist before any native build.
tasks.configureEach {
    if (listOf("configureCMake", "buildCMake", "generateJsonModel", "externalNativeBuild").any { name.startsWith(it) }) {
        dependsOn(extractSpeechRuntime)
    }
}
