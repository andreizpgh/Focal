import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: transcript reconciliation, decode policy and the level meter. No Android.
plugins { alias(libs.plugins.kotlin.jvm) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
