package com.filesearch.android

import android.app.Application
import android.content.Context

/**
 * Production app entry point.
 *
 * ML Kit OCR (TextRecognition.getClient) throws
 * `IllegalStateException: MlKitContext has not been initialized` unless
 * `MlKit.initialize` runs first — the F017 parity probe hit this on device
 * because no app/test setup called it. Production OCR needs the same init,
 * so it lives here (not test-only).
 *
 * Reflective on purpose: text-recognition is still an androidTest-only dep,
 * so a direct `MlKit` import would break the :android main compile. When
 * production extraction promotes it to `implementation`, replace with direct
 * calls.
 *
 * Explicit registrars on purpose: manifest discovery
 * (MlKitComponentDiscoveryService) only sees components merged into the app
 * APK manifest — test-only deps never merge there, so plain initialize leaves
 * the context empty and getClient NPEs. Register the bundled-Latin
 * components directly whenever they are on the classpath.
 */
class FileSearchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initMlKit()
    }

    private fun initMlKit() {
        val registrars =
            listOf(
                "com.google.mlkit.common.internal.CommonComponentRegistrar",
                "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
                "com.google.mlkit.vision.text.internal.TextRegistrar",
            ).mapNotNull { name ->
                runCatching { Class.forName(name).getDeclaredConstructor().newInstance() }.getOrNull()
            }
        runCatching {
            if (registrars.size == 3) {
                Class.forName("com.google.mlkit.common.sdkinternal.MlKitContext")
                    .getMethod("initializeIfNeeded", Context::class.java, List::class.java)
                    .invoke(null, this, ArrayList(registrars))
            } else {
                Class.forName("com.google.mlkit.common.MlKit")
                    .getMethod("initialize", Context::class.java)
                    .invoke(null, this)
            }
        }
    }
}
