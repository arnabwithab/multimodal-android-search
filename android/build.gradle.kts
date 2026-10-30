// Android shell module (v1): plain Activity, no Compose, no engine coupling.
// Choice: NO project(":app") dependency. :app is kotlin("jvm")-only; wiring it
// as an Android dependency (or shared sourceDir) risks breaking `make test`.
// Engine sharing comes later (extract engine to a Kotlin library module); the
// shell only needs to install + launch for now.
plugins {
    id("com.android.application") version "8.10.1"
    kotlin("android") version "2.3.21"
}

android {
    namespace = "com.filesearch.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.filesearch.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Pinned (not latest.release) for reproducible bench numbers.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    androidTestImplementation("androidx.work:work-testing:2.9.0")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Voice keys at rest (arch §7): EncryptedSharedPreferences only.
    implementation("androidx.security:security-crypto:1.1.0")
    // F017 extraction parity (device-only; engine untouched):
    // axet fork exposes FPDFText (page text layer); barteksc/mhiew are render-only.
    androidTestImplementation("com.github.axet:pdfium:2.0.16")
    androidTestImplementation("com.google.mlkit:text-recognition:16.0.1") // Latin OCR, bundled model
    androidTestImplementation("androidx.media3:media3-transformer:1.4.1")
    androidTestImplementation("androidx.media3:media3-extractor:1.4.1")
    // Promote back to implementation when production extraction lands.
    androidTestImplementation("androidx.test:core:1.6.1")
}
