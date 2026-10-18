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
}
