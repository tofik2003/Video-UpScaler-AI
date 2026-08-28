plugins {
    id("com.android.application")
    // Compose compiler. No kotlin-android plugin — AGP 9 provides Kotlin built in.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.videoupscaler.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.videoupscaler.ai"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Models must be memory-mappable, not inflated into the APK's compressed stream.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    // Compose BOM 2026.04.01 (Compose 1.11) is pinned deliberately: BOM 2026.08.00 (Compose 1.12)
    // requires compileSdk 37 and AGP 9.1.1+, which would break the documented AGP/Kotlin pairing
    // above. Move both together, not one at a time.
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ComponentActivity + ActivityResult (SAF picker) + setContent.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // Media3 — the whole pipeline. media3-effect carries GlEffect / BaseGlShaderProgram /
    // LanczosResample, which is the extension point the architecture rests on.
    val media3 = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // LiteRT — Interpreter line, minSdk 21. Not used by Phase 1; pinned so the coordinates are
    // proven to resolve before Phase 2 depends on them. Costs APK size until then.
    val litert = "1.4.2"
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
}
