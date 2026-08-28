# Getting Video UpScaler AI onto an Android device

Last checked: **2026-08-28**. Version numbers below were verified against vendor release pages on that
date; re-check them before you start, because the Android toolchain moves every few weeks.

---

## 0. Read this first — what actually exists

The repository now contains a **Phase 1 implementation**, uncompiled and incomplete:

```
build.gradle.kts                                  AGP 9.0.1 + Compose compiler 2.2.10
settings.gradle.kts
gradle.properties
app/build.gradle.kts                              compileSdk 36, minSdk 24, Media3 + LiteRT + activity
app/src/main/AndroidManifest.xml
app/src/main/java/com/videoupscaler/ai/MainActivity.kt              Compose host + SAF picker
app/src/main/java/com/videoupscaler/ai/UpScalerApp.kt               process-scoped exporter
app/src/main/java/com/videoupscaler/ai/pipeline/UpscaleChain.kt     shared effect chain (Tier 0)
app/src/main/java/com/videoupscaler/ai/pipeline/VideoExporter.kt    export + progress + pre-flight
app/src/main/java/com/videoupscaler/ai/ui/EnhanceScreen.kt          Compose workspace
app/src/main/java/com/videoupscaler/ai/ui/EnhanceViewModel.kt       state + exporter bridge
app/src/main/java/com/videoupscaler/ai/ui/theme/Theme.kt            Material 3 theme
app/src/main/res/values/strings.xml
docs/ci-workflow.yml                              copy to .github/workflows/ to enable CI
docs/PLAN.md, docs/DESIGN.md, docs/plan.json, docs/BUILD_AND_RUN.md
```

What is missing, and why it matters:

| Piece | Status |
|---|---|
| Gradle **wrapper** (`gradlew`, `gradle-wrapper.jar`) | ❌ absent — the jar is binary and cannot be authored here. CI uses the runner's system Gradle; locally, run `gradle wrapper` once. |
| `.github/workflows/android.yml` | ❌ absent — the push was rejected (see §1a). Definition is in `docs/ci-workflow.yml`. |
| **Compilation verified?** | ❌ **No.** Never compiled. See §5. |
| Compose UI | ✅ Material 3, dynamic colour, dark default |
| `.tflite` model | ❌ does not exist anywhere, so there is no AI upscaling |
| Tests | ❌ none |

So "run the app on a device" is three steps, and only the first two are possible today:

| Step | Possible today? | Why |
|---|---|---|
| 1. Enable CI, get a first compile | ⚠️ needs your credentials | Workflow push is blocked here |
| 2. Install the APK; pick a video, export it upscaled with audio | ✅ yes, once it compiles | Pure Media3/OpenGL — no ML model needed |
| 3. Phase 2: real AI upscaling | ❌ **not yet** | **There is no trained model.** No `.tflite` exists in this repo. |

Step 3 is the one people underestimate. It is not "add a dependency" — it is a training and conversion
pipeline (§6). Plan for it as its own workstream.

---

## 1. Toolchain

Verified versions as of 2026-08-28:

| Component | Use | Source |
|---|---|---|
| Android Studio | **Quail 3 \| 2026.1.3** (current stable) | [releases](https://developer.android.com/studio/releases) |
| Android Gradle Plugin | **9.0.1** | [about-agp](https://developer.android.com/build/releases/about-agp) |
| Gradle | **9.7.1** (AGP 9.0 needs ≥ 9.1.0) | same |
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

## 1a. Building in GitHub Actions instead of locally

**Yes, this works, and it is the right choice if you have no local toolchain.** GitHub's
`ubuntu-latest` runner (Ubuntu 24.04, image `20260823.283.1`, verified 2026-08-28) pre-installs exactly
what this project needs:

| Pre-installed | Version | Needed for |
|---|---|---|
| Java | **17.0.20 (default)**, 21, 25 | AGP 9 requires 17+ |
| Gradle | **9.7.1** | AGP 9.0.1 needs ≥ 9.1.0 ✅ |
| Android build-tools | **36.0.0**, 36.1.0, 37.0.0, 35.x, 34.0.0 | `compileSdk 36` ✅ |
| Android platforms | **android-36**, 36.1, 37.0, 37.1, 35, 34 | ✅ |
| Platform-tools (`adb`) | 37.0.1 | ✅ |
| NDK | 27.3 (default), 28.2, 29.0 | Phase 2 zero-copy path |
| CMake | 3.31.5, 4.1.2 | Phase 2 |
| `ANDROID_HOME` | `/usr/local/lib/android/sdk` | set as an env var already |

No `setup-android` step is required, and no wrapper is needed — call the system `gradle` directly.
The workflow in `docs/ci-workflow.yml` does exactly this.

### Getting from a CI artifact to your phone

```bash
# download the APK that CI uploaded
gh run download <run-id> --name app-debug-apk --dir ./apk
adb install -r ./apk/app-debug.apk
```

Or use the Actions web UI → run → **app-debug-apk** artifact.

### Three things CI cannot do for this project

1. **It cannot verify quality or performance.** The runner is an Azure VM with a virtualised GPU. It will
   compile the LiteRT GPU delegate happily and tell you nothing about real inference speed. Phase 0
   measurements (`PLAN.md` §7) still need a physical phone.
2. **It cannot run an emulator usefully.** `ubuntu-latest` has `xvfb` and emulators can be started
   (`reactivecircus/android-emulator-runner`), but without KVM it is slow, and the GPU delegate still
   won't behave like a real Adreno/Mali. Worth it for instrumentation tests, not for this.
3. **It cannot prove the AI path works** — there is no model to load (§6).

### The permission problem you will hit

The workflow file **cannot be committed from this environment**. The push was rejected with:

```
remote: refusing to allow a GitHub App to create or update workflow
        `.github/workflows/android.yml` without `workflows` permission
```

The GitHub App token used here lacks the `workflows` scope. That is why the definition lives at
`docs/ci-workflow.yml`. **You** need to move it into place with your own credentials:

```bash
mkdir -p .github/workflows
cp docs/ci-workflow.yml .github/workflows/android.yml
git add .github/workflows/android.yml
git commit -m "ci: add Android build"
git push
```

The file has a header comment with these instructions. Once committed, it runs on every push and PR.

### Consequence: nothing here has been compiled

Because the workflow could not be committed, **no CI run has ever executed on this repository** —
verified with `gh run list`, which returns empty. So the skeleton in `app/` is unverified. The first
push that installs the workflow *is* the first compile, and it may well fail on something small (a DSL
method name, a Kotlin/Java target mismatch). That is expected; fix forward.

The highest-risk items in the skeleton, ranked:

1. **AGP 9 built-in Kotlin vs. `compileOptions`** — if Kotlin's `jvmTarget` disagrees with Java 17 you
   get "Inconsistent JVM-target compatibility". Fix: add a `kotlin { compilerOptions { ... } }` block,
   or drop `compileOptions` and let AGP default.
2. **The Compose/Kotlin/AGP pairing** — the compose-compiler plugin version must equal the Kotlin
   Gradle Plugin version that AGP bundles. AGP 9.0.1 documents KGP 2.2.10, which is why AGP is
   pinned there rather than at 9.3.2. See §1a. If this breaks, the error mentions the compose
   compiler and a Kotlin version, and the fix is to re-pin the plugin to the bundled KGP.
3. **`androidResources { noCompress += "tflite" }`** — DSL shape moved between AGP versions. If it
   errors, delete the block; it only matters once a model exists.
4. **`@OptIn(UnstableApi::class)` on `PlayerView`** — needed or the build fails on the unstable-API lint.
5. **Gradle 9.7.1 vs AGP 9.0.1** — above AGP's stated minimum (9.1.0) and untested by me. If it
   complains, pin Gradle via `gradle/actions/setup-gradle`.

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

## 3. The project

A skeleton already exists in this repo (`build.gradle.kts`, `settings.gradle.kts`,
`app/build.gradle.kts`, manifest, `MainActivity.kt`). Open the repo root in Android Studio and let it
sync — **that sync is the first real check that the build files are valid.**

The dependency block that matters:

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
   // LanczosResample has NO public constructor - use the static factory.
   player.setVideoEffects(listOf(LanczosResample.scaleToFitWithFlexibleOrientation(1920, 1080)))
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

## 5. Verification — what has and has not been proven

**Nothing in this repository has been compiled or executed.** Two independent blockers, both verified:

1. **This sandbox cannot build.** No JDK exists anywhere on the filesystem, no Android SDK, and bash has
   no outbound network — DNS resolves `dl.google.com` but every HTTPS request returns `000`, and
   `apt-get install openjdk-17-jdk-headless` fails with *"Unable to locate package"* against empty lists.
2. **CI could not be enabled either.** `gh` is installed and authenticated, and the intent was to push
   the workflow and let GitHub compile the code. The push was **rejected** because the GitHub App token
   lacks the `workflows` scope. `gh run list` confirms zero runs have ever happened on this repo.

So: version numbers and API signatures are verified against vendor sources; **the build itself is not**.

The checks that *were* run:

- `plan.json` parses and its structure is internally consistent.
- Every arithmetic claim in the plan recomputed.
- Media3 API signatures checked against the reference — which caught a real error I had written in
  `PLAN.md` §6.1 (`BaseGlShaderProgram` takes no `Context`; the per-frame hook is
  `drawFrame(int, long)`). Corrected.
- AGP pinned to `9.0.1`, not the latest `9.3.2`, for the documented KGP 2.2.10 pairing (§1a).
- Every Compose, Material 3, Activity, and Lifecycle coordinate checked against Maven metadata.
- Runner image contents read from the published `Ubuntu2404-Readme.md`.

The first compile is the gate. Enable the workflow (§1a), or run locally:

```bash
gradle wrapper --gradle-version 9.7.1   # once, to create ./gradlew
./gradlew :app:assembleDebug            # does it compile
./gradlew :app:lintDebug                # catches manifest/permission mistakes
./gradlew :app:installDebug             # gets it onto the phone
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

1. **Enable CI** — copy `docs/ci-workflow.yml` to `.github/workflows/android.yml` and push (§1a).
   The first run either produces an APK artifact or gives you a precise compile error. Do this before
   anything else; it is the only way to learn whether the skeleton is valid.
2. **Get one physical device recognised** — `adb devices` should list it as `device`.
3. **Install the CI APK on the phone** (`gh run download` → `adb install`). **Proves the whole
   build-to-device path end to end, before you add a single feature.**
4. Add the SAF picker and ExoPlayer preview. **Proves you can read a video.**
5. Add `LanczosResample` + a CAS shader effect; see it change in preview. **Proves the effect seam works.**
6. Add Transformer export with audio; check the output plays and is in sync. **Proves the pipeline.**
7. Only now: start the model pipeline (§6).
8. Then Phase 0 measurement (`PLAN.md` §7) on real hardware, before committing to the AI architecture.

Each step isolates one thing. Steps 1–6 are days; step 7 is weeks.
