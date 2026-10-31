// Android shell module (v1): plain Activity, no Compose, no engine coupling.
// Choice: NO project(":app") dependency. :app is kotlin("jvm")-only; instead
// :android compiles the same engine sources directly (srcDir below) — one
// source of truth, zero mirrors. Every engine import (java.io/nio/text/
// security/concurrent, kotlin.math; java.sql only in IndexStore, which
// compiles on Android but is never called on device) already resolves under
// the Android bootclasspath, so this is a one-liner with no file moves and
// `make test` (:app:test only) untouched. A :core library module is the
// long-term home once device-only deps arrive.
// shortcut: graduate this srcDir into a :core module when Hilt/device-only deps land.
plugins {
    id("com.android.application") version "8.10.1"
    kotlin("android") version "2.3.21"    // F020 Compose UI binding (thin; engine logic stays in :app via hand-mirrored params).
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

    // F022: compile engine sources directly (see header comment).
    sourceSets {
        getByName("main").java.srcDir(rootProject.file("app/src/main/java"))
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
    // F022 device index: bundled SQLite — the framework SQLite has no FTS5
    // module (recorded on SM8635); arch §5 needs the exact porter/unicode61
    // tokenizer and BundledSQLiteDriver officially supports FTS5. Same IndexSchema DDL.
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    // F017 extraction parity (device-only; engine untouched):
    // axet fork exposes FPDFText (page text layer); barteksc/mhiew are render-only.
    // Promoted to implementation for inline PDF first-page preview (MediaPreview);
    // same artifact the review flagged "promote back when production lands".
    implementation("com.github.axet:pdfium:2.0.16")
    implementation("com.google.mlkit:text-recognition:16.0.1") // Latin OCR, bundled model
    androidTestImplementation("androidx.media3:media3-transformer:1.4.1")
    androidTestImplementation("androidx.media3:media3-extractor:1.4.1")
    // Inline audio preview (MediaPreview + MainActivity player): in-tab only,
    // no background playback. Pinned to the same 1.4.1 as transformer/extractor.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // Promote back to implementation when production extraction lands.
    androidTestImplementation("androidx.test:core:1.6.1")
    // F020 Compose UI binding (BOM only; text buttons, no icons lib).
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Mic / nav / status icons (F020 UI polish): core + extended for Icons.Filled.Mic et al.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose")
    // F020 Paparazzi snapshots (JVM unit tests, no device).
    testImplementation("junit:junit:4.13.2")
    testImplementation("app.cash.paparazzi:paparazzi:2.0.0-alpha02")
    // F020 instrumented skeleton (phone-only; not run on JVM).
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
