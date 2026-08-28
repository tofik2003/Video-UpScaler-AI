plugins {
    id("com.android.application")
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
    // ComponentActivity + the ActivityResult API, used for the SAF picker.
    implementation("androidx.activity:activity:1.13.0")

    // Media3 — the whole pipeline. media3-effect carries GlEffect / BaseGlShaderProgram /
    // LanczosResample, which is the extension point the architecture rests on.
    val media3 = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // LiteRT — Interpreter line, minSdk 21. Unused by the skeleton but pinned now so the
    // coordinates are proven to resolve before Phase 2 depends on them.
    val litert = "1.4.2"
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
}
