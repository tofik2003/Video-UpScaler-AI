# 02 — Technical Architecture

Companion to `00-MASTER-PLAN.md` and `03-AI-ML-MODELS.md`.

---

## 1. Tech stack (opinionated defaults)

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin 2.x (JVM + JNI bridging to C++ where needed) | Coroutines, Compose, tooling |
| UI | Jetpack Compose + Material 3, single Activity | Modern, less code, dynamic color |
| Navigation | Compose Navigation (typed routes) | Simple; revisit Navigation 3 when stable |
| DI | Hilt | Standard, KSP support |
| Async | Coroutines + Flow (`Dispatchers.Default` for bitmap, dedicated single-thread dispatcher serializes GPU work) | Backpressure-friendly progress |
| Persistence | DataStore (Preferences) for settings; Room only if "Revived" gallery lands (P1) | Minimal |
| Image loading | Coil (compose) | Compose-native |
| Background jobs | WorkManager + Foreground Service (`dataSync` type) for queue | Process-death safe, visible progress |
| ML runtime | **ONNX Runtime Mobile** *or* **ncnn** — decided by Phase-2 benchmark gate (doc 03 §4, doc 08) | Vulkan GPU, fp16, per-licence friendly |
| Face detection | ML Kit Face Detection (bundled, on-device) | Fast, free, offline |
| Native layer | C++17 module via CMake/NDK; JNI surface kept tiny (5–8 functions) | Wraps runtime, tiling loops, blend |
| Billing | Play Billing (latest 8.x) | Play requirement |
| Quality | Crashlytics + Play Vitals; opt-in Firebase Analytics (doc 11) | Industry default |
| Build | Gradle version catalog + convention plugins; AGP current stable; R8 always on | Doc 06 |

## 2. Module map (Gradle multi-module)

```
:app                      Compose UI, navigation, Hilt wiring, manifest, DI graph
├── :core:common          dispatchers, Result types, logging, utils, time
├── :core:designsystem    theme (M3, dynamic color), reusable components (CompareSlider…)
├── :core:model           pure domain entities (EnhanceRequest, DeviceTier, ModelInfo…)
├── :core:media           decode/encode, EXIF, color, MediaStore/SAF (doc 04)
├── :core:data            repositories: settings, models, results, billing
├── :ml:api               interfaces: UpscaleEngine, RestoreEngine, BenchmarkProbe
├── :ml:runtime-onnx      ONNX Runtime impl (+ .so packaging)      ┐ exactly one ships
├── :ml:runtime-ncnn      ncnn impl (+ .so packaging)              ┘ (other kept for A/B)
├── :ml:model-manager     manifest parsing, download+verify (SHA-256), storage, versions
├── :feature:onboarding   privacy onboarding (3 screens)
├── :feature:gallery      pick/recent list, albums
├── :feature:enhance      viewer + compare slider + config sheet
├── :feature:queue        batch queue UI + foreground service wiring
├── :feature:results      result compare, save/share
├── :feature:settings     defaults, storage, diagnostics, model manager UI
└── :feature:paywall      pricing screen + billing UI
```

Rules: features never touch each other (navigate via routes); only `:ml:*` knows about
inference; only `:core:media` touches bitmaps/EXIF; `:core:model` has zero Android deps.

## 3. Layered data flow for one enhance job

```
UI (Compose ViewModel)
  │  EnhanceRequest(uri, scale, preset, denoise, format)
  ▼
EnhanceCoordinator (domain, :feature:enhance or :core:domain)
  │  1. resolve DeviceTier (cached)
  │  2. ensure ModelSet downloaded (:ml:model-manager)
  │  3. decode & plan ( :core:media → DecodePlan, tile grid, memory budget)
  │  4. run pipeline (:ml:api engines, C++ tiles loop)
  │  5. encode + persist (EXIF copy, MediaStore insert)
  ▼
ProgressBus: StateFlow<EnhanceProgress(tilesDone, tilesTotal, etaMs, stage)>
  ▼
UI renders progress; cancel → coroutine cancel → native loop checks atomic flag
```

### Threading contract
- Exactly **one** inference worker at a time (GPU contexts don't like concurrency) — a single-threaded `@InferenceDispatcher` serializes work.
- Decode/encode on `Dispatchers.Default`; UI on Main; native calls are blocking and suspending from Kotlin's view.
- Foreground service owns long jobs so backgrounding never kills a run (US-015).

## 4. Key sequence — single image 4×

```
Picker ─▶ contentResolver.openInputStream ─▶ ImageDecoder (bounds, EXIF, orientation)
      ─▶ DecodePlan {targetW/H, tiles[N], tile=256, overlap=16, model=realesr-x4}
      ─▶ per tile:  normalize(0..1, fp16) ─▶ engine.run(tile) ─▶ feather-merge into output FBO/Bitmap
      ─▶ (faces) ML Kit detect ─▶ per-face crop upscale/restore ─▶ feather-paste
      ─▶ post: optional micro-sharpen (US-12 strength) ─▶ encode (JPEG95/HEIF/PNG)
      ─▶ ExifInterface copy (date, camera; strip GPS) ─▶ MediaStore.insert
      ─▶ Result(uri) ─▶ compare screen
```

## 5. State management & error policy

- Each feature: one `StateFlow<UiState>` + `SharedFlow<Effect>` (navigation/toasts). No shared mutable state.
- Pipeline errors are **typed**: `DecodeError`, `ModelMissingError(needsDownload)`, `OomRiskError(suggestDownscale)`, `InferenceError`, `EncodeError`. Every error has a user message + a recovery action. No naked exceptions cross the domain boundary.
- Retry policy: WorkManager `BackoffPolicy.EXPONENTIAL` for queue items; model downloads resumable (HTTP Range).

## 6. Device capability service

`DeviceTier` computed at first launch (cached, invalidated on OS upgrade):
- RAM (`ActivityManager.MemoryInfo`), SoC (`Build.SOC_MANUFACTURER/MODEL`, API 31+), GPU vendor (`GL_RENDERER`), Android API, thermal headroom probe.
- Tiers: LOW (<4 GB) / MID (4–7 GB) / HIGH (8 GB + known flagship SoC) / MAX (12 GB + cooling) → defaults per doc 08 §3.
- Exposed to UI for honest feature gating ("4× needs a stronger device on huge images — try 2×").

## 7. Model manager (:ml:model-manager)

- `models-manifest.json` (remote, versioned) lists each model: `{id, task, variant, version, sizeBytes, sha256, url, minTier, license}`.
- Bundled: one compact x2/x4 photo model inside the APK assets (≤ 8 MB fp16).
- Others: on-demand download (PAD asset pack **or** CDN ZIP for post-install updates; chosen in doc 03 §8).
- Storage: `filesDir/models/<id>/<version>/` with `.verified` marker; verification = SHA-256 before first use; atomic replace on update; "Free up space" in Settings.

## 8. Native (C++) surface — kept deliberately tiny

```cpp
// jni_bridge.cpp — the ONLY JNI entry points
Init(runtimeConfig) -> Handle
LoadModel(handle, modelPath, delegate /*vulkan|gl|cpu*/) -> ModelHandle
RunTile(modelHandle, inF16 buffer, w, h, outF16 buffer) -> Status   // hot loop
RunInfo(handle) -> {delegate, threads}                              // diagnostics
BenchTile(modelHandle, size) -> {msMin, msMed}                      // tier probe
FreeModel / Destroy
```
Everything else (tiling grid math, blending, color) is Kotlin or C++ internal — the JNI
boundary stays auditable and 16 KB-page safe (doc 06 §7).

## 9. Architecture decision records (ADRs) to write as we build

| ADR | Question | Default |
|---|---|---|
| ADR-1 | ONNX Runtime vs ncnn | Benchmark gate end of Phase 2 (doc 08 §6) |
| ADR-2 | One output pipeline vs per-format encoders | Single `ImageWriter` abstraction; libheif bundled if system HEIF encoder weak |
| ADR-3 | Foreground service type | `dataSync` (long-running user-initiated work), fallback expedited Work |
| ADR-4 | Photo Picker vs READ_MEDIA_IMAGES | Photo Picker first (no permission); media permission only for in-app gallery browsing |
| ADR-5 | Compose Navigation arg passing | Type-safe routes + kotlinx.serialization |
