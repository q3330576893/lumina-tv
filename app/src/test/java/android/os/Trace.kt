package android.os

/** JVM-only platform tracing shim for the real Compose runtime (the SDK android.jar throws Stub!).
 * This has no playback/Compose state behavior and is never packaged in the Android application.
 */
object Trace {
    @JvmStatic fun beginSection(sectionName: String) = Unit
    @JvmStatic fun endSection() = Unit
}
