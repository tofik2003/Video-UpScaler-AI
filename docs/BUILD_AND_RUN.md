# Getting Video UpScaler AI onto an Android device

Last checked: **2026-08-28**. Version numbers below were verified against vendor release pages on that
date; re-check them before you start, because the Android toolchain moves every few weeks.

---

## 0. Read this first — what actually exists

The repository currently contains **three Markdown files and no application code**:

```
README.md
docs/PLAN.md
docs/plan.json
docs/BUILD_AND_RUN.md
```

There is no Gradle project, no manifest, no Kotlin source, and no `.tflite` model. So "run the app on a
device" is not one step — it is three, and only the first two are possible today:

| Step | Possible today? | Why |
|---|---|---|
| 1. Toolchain + device setup | ✅ yes | Standard Android setup |
| 2. Phase 1 skeleton: pick a video, upscale with Lanczos + sharpen, export with audio | ✅ yes | Needs no ML model — pure OpenGL/Media3 |
| 3. Phase 2: real AI upscaling | ❌ **not yet** | **There is no trained model.** No `.tflite` file exists in this repo, and none is downloadable from it. |

Step 3 is the one people underestimate. It is not "add a dependency" — it is a training and conversion
pipeline (§6). Plan for it as its own workstream.

---

## 1. Toolchain

Verified versions as of 2026-08-28:

| Component | Use | Source |
|---|---|---|
| Android Studio | **Quail 3 \| 2026.1.3** (current stable) | [releases](https://developer.android.com/studio/releases) |
| Android Gradle Plugin | **9.2** | [about-agp](https://developer.android.com/build/releases/about-agp) |
| Gradle | **9.4.1** (AGP 9.2's minimum) | same |
| JDK for the build | **17+** (Studio bundles 21) | [AGP 9.0 notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes) |
| `compileSdk` / `targetSdk` | **36** | see note below |
| Media3 | **1.11.0** | [releases](https://developer.android.com/jetpack/androidx/releases/media3) |
| LiteRT (Interpreter line) | **1.4.2**, minSdk 21 | [LiteRT for Android](https://developers.google.com/edge/litert/android) |
| Compose BOM | **2026.04.01** (Compose 1.11) | [Compose releases](https://developer.android.com/jetpack/androidx/releases/compose-runtime) |

### Two toolchain traps

**A. Do not blindly take the newest Compose BOM.** BOM `2026.08.00` (Compose 1.12) forces
**`compileSdk 37` and AGP 9.1.1+**. If you want to stay on `compileSdk 36`, pin BOM `2026.04.01`. If you
do move to 37, bump AGP in the same commit or the build fails.

**B. AGP 9.0+ has built-in Kotlin, enabled by default.** Do **not** apply the
`org.jetbrains.kotlin.android` plugin — doing so conflicts with the built-in support. This breaks
tutorials from 2024–2025.

**C. The Play Store target-SDK floor rises to API 36 on 2026-08-31** — three days from now. New app
submissions must target 36+. Irrelevant for local testing, relevant the moment you publish.

---

## 2. Choosing a device — this matters more than it looks

| | Emulator (AVD) | Physical device |
|---|---|---|
| Build the app, test UI | ✅ fine | ✅ |
| Test Tier 0/1 (Lanczos, FSR shader) | ✅ acceptable | ✅ |
| **Measure AI inference speed** | ❌ **meaningless** | ✅ required |
| Test LiteRT **GPU delegate** | ❌ falls back to CPU or software GL | ✅ real OpenCL |
| Test thermal throttling | ❌ | ✅ required |

The emulator's GPU is host-virtualised. Your Phase 0 numbers (§7 of the plan) will be wrong on it — often
wrong *optimistically*. **Use a physical phone for anything involving the model.** Ideally two: one
Adreno (Snapdragon), one Mali (Exynos/Dimensity), since the LiteRT GPU delegate picks different backends
and driver bugs are vendor-specific.

### Enabling a physical device

1. **Settings → About phone → tap Build number 7 times** → Developer options enabled.
2. **Settings → Developer options → USB debugging** on.
3. Connect by USB, accept the RSA prompt on the phone.
4. Verify:
   ```bash
   adb devices -l        # should list your device with "device" (not "unauthorized")
   ```
   `adb` lives in `$ANDROID_HOME/platform-tools` — add it to `PATH` or use the full path.

Wireless is also fine: **Developer options → Wireless debugging → Pair device with pairing code**, then
`adb pair <ip>:<port>` and `adb connect <ip>:<port>`.

---

## 3. Creating the project

Fastest correct path — let Android Studio generate the skeleton, then edit:

1. Android Studio → **New Project → Empty Activity** (the Compose template).
2. Name `Video UpScaler AI`, package `com.videoupscaler.ai`, language **Kotlin**, build configuration
   **Kotlin DSL**.
3. Minimum SDK: **API 24** to start. (LiteRT's Interpreter line supports 21; the zero-copy CompiledModel
   path later needs 23+. 24 is a sensible floor and covers ~99% of active devices.)
4. Let it finish the first Gradle sync.

Then replace the generated `app/build.gradle.kts` dependencies with:

```kotlin
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

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
}

dependencies {
    // Compose — pin the BOM so all compose artifacts agree
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose")

    // Media3 — the pipeline. All four, same version.
    val media3 = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-effect:$media3")      // GlEffect lives here
    implementation("androidx.media3:media3-transformer:$media3") // export
    implementation("androidx.media3:media3-ui:$media3")          // PlayerView

    // LiteRT — Phase 2 only; harmless to add now
    val litert = "1.4.2"
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu-api:$litert")

    // Background export — Phase 3
    implementation("androidx.work:work-runtime-ktx:2.10.1")  // verify current stable at build time

    implementation("androidx.core:core-ktx:1.17.0")           // verify current stable at build time
}
```

**`media3-effect` is the module people forget**, and it is the one containing `GlEffect`,
`BaseGlShaderProgram`, and `LanczosResample` — the entire extension point the architecture rests on.

---

## 4. What to build first (the runnable subset)

This is Phase 1 from the plan. It runs on a device today, with no model, and it validates the whole
pipeline — decode → effect chain → encode → mux, with audio.

1. **`AndroidManifest.xml`** — permissions and the service:
   ```xml
   <!-- No READ_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE. Use SAF instead. -->
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

   <application ...>
       <service
           android:name=".export.ExportService"
           android:foregroundServiceType="mediaProcessing"
           android:exported="false" />
   </application>
   ```
   `mediaProcessing` needs API 35+; guard with `dataSync` below that. Request `POST_NOTIFICATIONS` at
   runtime on API 33+ or the progress notification silently never appears.

2. **Pick a file via SAF** — `ActivityResultContracts.OpenDocument()` with `arrayOf("video/*")`. Take a
   persistable URI permission, or the URI dies on reboot and every queued export breaks.

3. **Preview** — `ExoPlayer` + `PlayerView`, then:
   ```kotlin
   player.setVideoEffects(listOf(LanczosResample(1920, 1080), CasSharpenEffect(0.35f)))
   ```

4. **Export** — the shape that actually compiles on Media3 1.11:
   ```kotlin
   val edited = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
       .setEffects(Effects(/* audioProcessors = */ listOf(), /* videoEffects = */ chain))
       .build()

   Transformer.Builder(context)
       .setVideoMimeType(MimeTypes.VIDEO_H265)
       .setAudioMimeType(MimeTypes.AUDIO_AAC)   // audio preserved — do not skip this
       .addListener(listener)
       .build()
       .start(edited, outputPath)               // outputPath: a String file path
   ```
   ⚠️ `Transformer.Builder.setVideoEffects()` was **removed in Media3 1.6.0**. Older tutorials use it;
   it will not compile. Effects go on the `EditedMediaItem`.

5. **Run it:**
   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:installDebug
   adb shell am start -n com.videoupscaler.ai/.MainActivity
   adb logcat --pid=$(adb shell pidof -s com.videoupscaler.ai)
   ```

---

## 5. Verification — and why I could not run it here

**I did not compile or run any of this.** This sandbox has no JDK, no Android SDK, and no outbound
network (verified: DNS resolves but every HTTPS connection returns `000`, and `apt` cannot reach its
sources). There is no way for me to execute `./gradlew` here.

So the code in §3–§4 and in `PLAN.md` §6 is **design, checked against API documentation, not compiled**.
The checks I *did* run:

- Verified `plan.json` parses and its structure is consistent.
- Verified every arithmetic claim in the plan by computation.
- Verified API signatures against the androidx/LiteRT reference pages — which caught a real error I had
  written in `PLAN.md` §6.1 (`BaseGlShaderProgram` does not take a `Context`, and the per-frame hook is
  `drawFrame(int, long)`, not a `queueInputFrame` with a `GlTextureProducer`). That is now corrected.

Run these locally; they are the real gate:

```bash
./gradlew :app:assembleDebug        # does it compile
./gradlew :app:lintDebug            # catches manifest/permission mistakes
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:installDebug         # gets it onto the phone
```

---

## 6. Getting to real AI upscaling (the part that isn't ready)

You need a `.tflite` model. None exists in this repo. The pipeline is:

1. **Train.** ESPCN is small and trains in hours on a single GPU. PyTorch is the usual starting point.
2. **Convert.** PyTorch → TFLite via Google's `ai-edge-torch` (`TorchConverter`). Note `tf.lite.TFLiteConverter`
   works from TensorFlow, so a PyTorch model goes through `ai-edge-torch`, not the TF converter.
3. **Quantise.** FP16 first (safe). INT8 later, with a quality gate — quantising a GAN generator is where
   banding comes from.
4. **Validate.** Compare against the PyTorch reference on the same frames before shipping. Silent
   numerical drift in conversion is common and produces "it works but looks worse".
5. **Bundle.** Put it in `app/src/main/assets/`. Add `androidResources { noCompress += "tflite" }` so it
   is memory-mappable. Watch APK size — past ~150 MB you want Play Asset Delivery.
6. **Check the licence** before bundling anything you did not train (`PLAN.md` §9).

Until step 5 produces a file, the app is a Lanczos/sharpen upscaler. That is a real product, and it is
the right thing to ship first — but do not describe it as AI.

---

## 7. First-run failures worth pre-empting

| Symptom | Cause |
|---|---|
| `Unresolved reference: GlEffect` | Missing `media3-effect` dependency |
| `setVideoEffects` unresolved | Removed in Media3 1.6.0 — use `EditedMediaItem.setEffects()` |
| Kotlin plugin conflict error | AGP 9 built-in Kotlin; remove `org.jetbrains.kotlin.android` |
| Compose needs `compileSdk 37` | You pulled BOM `2026.08.00`; pin `2026.04.01` or bump compileSdk + AGP |
| `SecurityException` on service start | Missing `FOREGROUND_SERVICE_MEDIA_PROCESSING` permission, or wrong `foregroundServiceType` for the API level |
| No notification appears | `POST_NOTIFICATIONS` not granted (API 33+) |
| Export succeeds, **video is silent** | You dropped the audio processor path or set `setRemoveAudio(true)` |
| Output colours look washed out | Colour/range handling — BT.601 vs BT.709, limited vs full range |
| `GpuDelegate must run on the same thread...` | Interpreter created off the GL thread — see `PLAN.md` §6.3 |
| App killed at ~6 h | `mediaProcessing` FGS budget; implement `onTimeout()` |

---

## 8. Suggested order

1. Set up toolchain + one physical device; confirm `adb devices` sees it.
2. Generate the project, sync, run the empty template. **Proves the toolchain before you add anything.**
3. Add Media3, wire SAF → ExoPlayer preview. **Proves you can read a video.**
4. Add `LanczosResample` + a CAS shader effect; see it change in preview. **Proves the effect seam works.**
5. Add Transformer export with audio; check the output plays and is in sync. **Proves the pipeline end to end.**
6. Only now: start the model pipeline (§6).
7. Then Phase 0 measurement (`PLAN.md` §7) on real hardware, before committing to the AI architecture.

Each step isolates one thing. Steps 2–5 are days; step 6 is weeks.
