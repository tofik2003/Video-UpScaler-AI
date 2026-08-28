// AGP 9.3.2 is the latest stable as of 2026-08-28 and requires Gradle >= 9.5.0.
// AGP 9.x has built-in Kotlin support enabled by default, so the
// org.jetbrains.kotlin.android plugin is deliberately NOT applied here.
plugins {
    id("com.android.application") version "9.3.2" apply false
}
