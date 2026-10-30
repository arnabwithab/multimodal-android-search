// Android shell module (v1): plain Activity, no Compose, no engine coupling.
// Choice: NO project(":app") dependency. :app is kotlin("jvm")-only; wiring it
// as an Android dependency (or shared sourceDir) risks breaking `make test`.
// Engine sharing comes later (extract engine to a Kotlin library module); the
// shell only needs to install + launch for now.
plugins {
    id("com.android.application") version "8.10.1"
    kotlin("android") version "2.3.21"
    // F020 Compose UI binding (thin; engine logic stays in :app via hand-mirrored params).
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    // F020 Paparazzi snapshots run on JVM (:android:testDebugUnitTest, no device).
    // 2.0.0-alpha02: sdk-common 31.10.1 == AGP 8.10.1's own (newer sdk-common
    // refactored GradleVersion and breaks AGP config; 1.3.5's layoutlib
    // predates compileSdk 36). Needs Gradle on JVM 21+ (Corretto 21 ~/jdks).
    id("app.cash.paparazzi") version "2.0.0-alpha02"
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

    // F020: Compose UI binding.
    buildFeatures {
        compose = true
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
    // F020 Compose UI binding (BOM only; text buttons, no icons lib).
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose")
    // F020 Paparazzi snapshots (JVM unit tests, no device).
    testImplementation("junit:junit:4.13.2")
    testImplementation("app.cash.paparazzi:paparazzi:2.0.0-alpha02")
    // F020 instrumented skeleton (phone-only; not run on JVM).
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
