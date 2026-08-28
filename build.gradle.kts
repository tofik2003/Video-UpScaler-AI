// Deliberate version pairing — see docs/BUILD_AND_RUN.md §1a.
//
// AGP 9.0 has a *documented* runtime dependency on Kotlin Gradle Plugin 2.2.10, so the Compose
// compiler plugin can be pinned to a version known to match. AGP 9.3.2's bundled Kotlin is not
// documented, and a compose-compiler/KGP mismatch fails the build in a way that is awkward to
// diagnose on a first-ever compile. Take the documented pairing first; upgrading AGP is then a
// separate, testable step.
//
// AGP 9.x has built-in Kotlin, so org.jetbrains.kotlin.android must NOT be applied — it conflicts.
// The Compose compiler plugin is still applied explicitly.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
