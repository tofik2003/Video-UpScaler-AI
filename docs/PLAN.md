# Video UpScaler AI — Engineering Plan (v2, revised)

**Tagline:** On-Device AI Video Enhancement Engine
**Architecture:** Privacy-first, on-device, GPU-resident frame pipeline (Media3 + LiteRT)
**Status:** Plan. Nothing in this repository is implemented yet — the repo currently contains only `README.md`.

> This document is a revision of the original v1 plan. Section 1 records what changed and why, so the
> corrections are auditable rather than silent.

---

## 1. Review of v1 — what changed and why

Ten findings. The first three are load-bearing; if they are not fixed the project fails after the
architecture is already written.

### 1.1 BLOCKER — The "zero-copy HardwareBuffer" code blueprint does not work

v1 shipped this as its reference implementation:

```kotlin
val inputs = arrayOf(inputBuffer)          // inputBuffer: HardwareBuffer
val outputs = mapOf(0 to outputBuffer)     // outputBuffer: HardwareBuffer
interpreter.runForMultipleInputsOutputs(inputs, outputs)
```

`Interpreter.runForMultipleInputsOutputs` accepts **only** multidimensional arrays or `java.nio.Buffer`
types — `ByteBuffer`, `FloatBuffer`, `IntBuffer`, `LongBuffer` (plus `String[]` for string tensors).
`android.hardware.HardwareBuffer` is none of those. This does not compile into a working path; at best
it throws at runtime. Every downstream decision in v1 that rested on "we hold textures in GPU memory and
hand handles straight to LiteRT" inherited a false premise.

**What actually exists:** true zero-copy GL/HardwareBuffer interop is a **C++ `CompiledModel` API**
feature in LiteRT 2.x — `TensorBuffer::CreateFromGlBuffer(...)` and
`TensorBuffer::CreateManaged(env, kLiteRtTensorBufferTypeAhwb, ...)`. There is no Java/Kotlin equivalent
on the `Interpreter` surface.

**Consequence for the plan:** "zero-copy" is a *phase-2, C++/NDK* objective, not a phase-1 Kotlin
property. v1 shipped as a two-copy pipeline and the plan must say so:

```
GL texture ──(PBO / glReadPixels)──► direct ByteBuffer ──► LiteRT GPU delegate ──► GL texture
   copy #1 (GPU→CPU)                                  copy #2 (CPU→GPU)
```

This is real, shippable, and measurable. It is not zero-copy. Claiming otherwise in v1 would have
hidden a genuine performance cliff until late integration.

### 1.2 BLOCKER — Media3 Transformer has no seam where v1 inserts inference

v1's flow is `MediaCodec → EGLSurface/HardwareBuffer → LiteRT → MediaCodec`, with "chunked block
processing via Media3 Transformer" as a separate pillar. Transformer does not expose raw decoded frames
between decode and encode; it is a closed pipeline. You cannot splice a model call into it from the
outside.

**The supported seam is `GlEffect` / `GlShaderProgram`.** Media3 explicitly documents implementing a
custom `GlShaderProgram` and doing per-frame work in `queueInputFrame()`, citing a MediaPipe
`FrameProcessor` as the example — i.e. running an ML model per frame inside the effect chain is the
intended extension point, and there is a Transformer + MediaPipe demo. Inference therefore lives in a
custom `GlEffect`, and Transformer runs decode→effect→encode as one pipeline.

This is a *better* architecture than v1's, not a compromise: the same `GlEffect` list drives
`ExoPlayer.setVideoEffects()` for preview and `Transformer` for export, so **preview and export cannot
drift visually**. v1 had two separate rendering paths and no mechanism keeping them identical.

Note the API also moved: `Transformer.Builder.setVideoEffects()` was **removed in Media3 1.6.0**.
Effects now go through `EditedMediaItem.Builder.setEffects(Effects(audioEffects, videoEffects))` +
`transformer.start(editedMediaItem, outputPath)`. Any v1 code written against the old signature will not
compile on a current Media3.

### 1.3 BLOCKER — Per-frame GAN super-resolution produces visible temporal flicker

v1's flagship model is FastSRGAN, a GAN. GAN super-resolution *hallucinates* high-frequency detail from
a single frame. Run independently per frame, the hallucination differs frame to frame, so edges and
textures crawl — the well-documented shimmering artifact of single-image SR applied to video. Suppression
requires multi-frame alignment, which no model in v1's matrix performs.

This compounds with the tier ordering: v1 put the flicker-prone model on the flagship path where users
are most quality-sensitive.

**Fix:** reorder tiers by *temporal determinism*, not raw sharpness.

| Tier | Method | Temporal behaviour | Role |
|---|---|---|---|
| 0 | Lanczos resample (Media3 built-in `LanczosResample`) + CAS sharpen | deterministic, zero flicker | universal floor, always ships |
| 1 | FSR 1.0 EASU + RCAS shader port (MIT-licensed) | deterministic | low-end / battery saver / live preview |
| 2 | ESPCN-style sub-pixel CNN | near-deterministic | **default** quality mode |
| 3 | GAN SR (Fast-SRGAN class) | flickers without stabilization | opt-in "max detail", labelled as such |

Tier 3 requires a temporal stabilizer (motion-gated blend against the previous output) and an explicit
user-facing warning. It is not the default.

Also added: Tier 0 did not exist in v1. Media3 already ships `LanczosResample`, which beats "FSR 1.0
fallback" as the universal floor — no shader port, no licensing, no ML, no flicker, ~1–3 ms.

### 1.4 The model matrix is arithmetically wrong

v1: *"FastSRGAN — target_upscale 2x / 4x (720p → 1080p)"*. 1280×720 at 2× is 1440p; at 4× it is 2880p.
Neither is 1080p — that ratio is 1.5×. The same 720p→1080p error reappears in `risk_mitigation`.

It matters because ESPCN/FastSRGAN are sub-pixel (PixelShuffle) models that support **integer** scale
factors only. You cannot ask one for 1.5×. Non-integer targets must be *run 2× then downsample*
(Lanczos), which costs GPU time and partially discards the detail you just synthesized. That is a real
design decision with a real cost, and v1 hid it inside an arithmetic slip.

Corrected matrix in §4. Every row now states input resolution, integer model scale, and post-scale
step explicitly.

### 1.5 The tagline contradicts the plan

v1 calls the engine "Real-Time" and then schedules it as a WorkManager background job with a persistent
notification. At v1's own 35–55 ms/frame, the ceiling is 18–29 fps **before** decode, colour conversion
and encode. That is not real-time for 30 or 60 fps sources.

Resolved by splitting the claim honestly:

- **Live preview** — Tiers 0/1 only, real-time by construction.
- **Export** — offline, Tiers 2/3, progress-driven, resumable.

The tagline is changed to *On-Device AI Video Enhancement Engine*, dropping "Real-Time".

### 1.6 Audio is never mentioned

v1's pipeline flow is `MP4 → decoder → GPU → encoder → MP4` and the word "audio" does not appear
anywhere. An upscaler that ships a silent video is broken regardless of image quality.

Added: audio passthrough vs re-encode policy, multi-track selection, and A/V sync at chunk boundaries.

### 1.7 Wrong foreground service type, and a hard 6-hour wall

v1 specifies a generic foreground service + WorkManager. Android 15 added a purpose-built
**`mediaProcessing`** foreground service type, described in the platform docs as appropriate "if your app
needs to transcode media from one format to another" — exactly this app. Using it (with `dataSync` as the
Android 14 and-below fallback) is the correct choice.

The bigger omission is the limit: `dataSync` and `mediaProcessing` services get **6 hours per 24-hour
period, shared across all of the app's services of that type**. On timeout the system calls
`Service.onTimeout(int, int)` and, if you do not `stopSelf()` within seconds, throws
`RemoteServiceException`. Starting another one after the budget is exhausted throws
`ForegroundServiceStartNotAllowedException`. v1's RAM/LMK framing did not anticipate this at all.

Added: `onTimeout()` implementation, a 6-hour budget tracker, and mandatory checkpoint/resume for long
exports (§6.4).

### 1.8 Dependency versions are 1.5–2 years stale, and one delegate is deprecated

| v1 | Reality (checked 2026-08-28) | Action |
|---|---|---|
| `media3-*:1.3.1` | **1.11.0** stable (2026-08-05) | upgrade; note the 1.6.0 effects API break |
| `tensorflow-lite:2.14.0` | TensorFlow Lite is now **LiteRT**. Interpreter line: **1.4.2** (minSdk 21). CompiledModel line: **2.2.0** (2026-08-14, minSdk 23, NDK r26a) | migrate coordinates |
| `tensorflow-lite-gpu:2.14.0` | `com.google.ai.edge.litert:litert-gpu:1.4.2` | migrate |
| NNAPI delegate (MobileSR row) | **deprecated in Android 15**; Play-services LiteRT ships no NNAPI delegate at all | remove; use GPU delegate |

NNAPI was a dead end on the MobileSR row — on a current Pixel the plan's chosen accelerator is the one
Google recommends migrating away from.

### 1.9 Every latency figure in v1 is unmeasured

v1 states "~12–18 ms/frame", "~35–55 ms/frame" as if measured. There is no benchmark harness, no device,
and no methodology behind them. This revision keeps target ranges but **labels them as targets** and adds
a Phase 0 measurement gate that must be passed before the architecture is committed (§7). Numbers become
real only when the harness produces them.

### 1.10 Risk section misdiagnoses the failure mode

v1's sole risk is LMK killing the process for exceeding RAM. A `mediaProcessing` foreground service is
largely protected from that. The real risks are GPU memory exhaustion from large intermediate textures,
thermal throttling (a 10-minute export at full GPU load *will* downclock), the 6-hour service budget,
temp storage exhaustion, and flicker-driven quality rejection. Replaced with a real register (§8).

---

## 2. Product scope

**In:** local video file → upscaled/enhanced local video file, entirely on device. No network, no cloud.

**Explicitly out of scope for v1:** live camera upscaling, frame interpolation, denoising as a separate
product surface, cloud offload, batch queueing of >1 job, editing/trimming UI.

**Non-negotiable:** a file that cannot be upscaled must still be *exportable unchanged* with audio
intact, rather than failing. Degrade, never brick.

---

## 3. Architecture

### 3.1 Pipeline

```
                          PREVIEW (real-time)
  MP4 (SAF) ─► ExoPlayer ─► VideoFrameProcessor ─┐
                                                 ├─► [shared GlEffect chain] ─► SurfaceView
                          EXPORT (offline)       │
  MP4 (SAF) ─► Transformer ─► decoder ───────────┘        │
                       ▲                                  ▼
                       └──── audio: passthrough/AAC ── encoder ─► muxer ─► MP4 (fMP4)
```

One effect chain, two hosts. Preview uses `ExoPlayer.setVideoEffects()`; export uses
`EditedMediaItem.setEffects()`. The chain is the single source of truth for what "enhanced" means.

### 3.2 The shared effect chain

Executed on Media3's GL thread, per frame:

1. **Colour normalisation** — Media3 supplies linear RGB and tells you the space via
   `GlEffect.toGlShaderProgram(context, useHdr)`. BT.709 for SDR, BT.2020 for HDR. Handle it or your
   output is a colour shift, which users read as "it made it worse".
2. **Base resample** — `LanczosResample` / `Presentation` to the exact target size. Deterministic.
3. **`AiUpscaleGlEffect`** *(custom)* — LiteRT inference, applied as a *residual blend* against the base
   resample rather than replacing it. Blend weight is user-controllable and is the honest answer to
   "the AI looks weird".
4. **Temporal stabilizer** *(custom, on by default for Tier 3)* — motion-gated blend with the previous
   output frame. Cheap, and it is the difference between watchable and shimmering.
5. **`SharpenGlEffect`** *(custom)* — CAS/RCAS final detail pass.

Rotation and display matrix are resolved by Media3 before the chain; do not re-handle them inside effects.

### 3.3 Capability detection at launch

Query once, cache, expose in settings so the user can override:

```
OpenCL present?  → Tier 2 candidate
GPU delegate compatible (CompatibilityList)? → Tier 2
RAM class + thermals → max target resolution
```

Fall through the tiers on *any* delegate-init failure. A GPU delegate that throws on a specific
Adreno driver must degrade to Tier 1, not crash the export.

---

## 4. Model matrix (corrected)

Latency columns are **Phase 0 targets, not measurements** (see §7).

| # | Model | Input | Integer scale | Post-scale | Target | Latency target | Backend | Tier |
|---|---|---|---|---|---|---|---|---|
| 1 | — (no ML) | any | — | Lanczos | any ≤4K | ~1–3 ms | GL shader | 0 |
| 2 | FSR 1.0 EASU+RCAS port | any | — | spatial | any ≤1080p | ~2–5 ms | GLES 3.0 shader | 1 |
| 3 | ESPCN sub-pixel CNN | 640×360 | 2× | — | 1280×720 | ≤20 ms | LiteRT GPU (OpenCL) | 2 |
| 4 | ESPCN sub-pixel CNN | 854×480 | 2× | — | 1708×960 | ≤35 ms | LiteRT GPU (OpenCL) | 2 |
| 5 | ESPCN sub-pixel CNN | 1280×720 | 2× | Lanczos →1080p | 1920×1080 | ≤60 ms | LiteRT GPU (OpenCL) | 2 |
| 6 | Fast-SRGAN class | 854×480 | 2× | — | 1708×960 | ≤55 ms | LiteRT GPU / Hexagon | 3 (opt-in) |
| 7 | Fast-SRGAN class | 1280×720 | 2× | Lanczos →1080p | 1920×1080 | ≤90 ms | LiteRT GPU / Hexagon | 3 (opt-in) |

Notes:
- No row promises a non-integer scale from the model. Where the target needs 1.5×, it is 2× + Lanczos
  downscale, and that cost is visible in the budget.
- 4× is dropped from v1. On a phone, 4× a 720p source is 2880p: the intermediate tensor alone
  (5120×2880×3×4 B ≈ 177 MB) is a memory hazard and the encode is the bottleneck anyway. Revisit only
  with evidence.
- Every model ships FP16 first. INT8 is a separate, later optimisation with its own quality gate —
  quantising a GAN generator is where banding comes from.

---

## 5. Dependencies (current, verified 2026-08-28)

```kotlin
// libs.versions.toml
[versions]
media3 = "1.11.0"
litert = "1.4.2"        // Interpreter API line; minSdk 21
work   = "2.10.x"       // pin to current stable at implementation time

[libraries]
media3-transformer = { module = "androidx.media3:media3-transformer", version.ref = "media3" }
media3-effect      = { module = "androidx.media3:media3-effect",      version.ref = "media3" }
media3-exoplayer   = { module = "androidx.media3:media3-exoplayer",   version.ref = "media3" }
media3-ui          = { module = "androidx.media3:media3-ui",          version.ref = "media3" }
litert             = { module = "com.google.ai.edge.litert:litert",       version.ref = "litert" }
litert-gpu         = { module = "com.google.ai.edge.litert:litert-gpu",   version.ref = "litert" }
litert-gpu-api     = { module = "com.google.ai.edge.litert:litert-gpu-api", version.ref = "litert" }
work-runtime-ktx   = { module = "androidx.work:work-runtime-ktx",     version.ref = "work" }
```

Removed from v1: everything under `org.tensorflow:*` (renamed to LiteRT) and the NNAPI delegate.

Phase 2 (zero-copy) additionally needs LiteRT **2.2.0** with the C++ `CompiledModel` API, NDK r26a,
minSdk 23, and a CMake target. That is a distinct dependency set, not an added line.

Alternative to evaluate in Phase 0: **LiteRT in Google Play services**
(`play-services-tflite-java` + `play-services-tflite-gpu`). It keeps the delegate out of your APK
(multi-MB saving) at the cost of a Play-services dependency. Decide after measuring both.

---

## 6. Corrected implementation blueprints

Illustrative — **not compiled or run**. This sandbox has no JDK, Android SDK, or Gradle, so no code in
this document has been built or executed. Treat as design, and validate in Phase 0.

### 6.1 The custom effect (the v1 blocker, fixed)

```kotlin
// androidx.media3.effect is @UnstableApi — the whole module needs @OptIn(UnstableApi::class).
class AiUpscaleEffect(
    private val modelAsset: String,
    private val scale: Int,                 // 2 or 3 — integer only
    private val blend: Float,               // 0.0 = passthrough, 1.0 = full AI
) : GlEffect {
    // This IS the correct signature: toGlShaderProgram(Context, boolean useHdr).
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        // Create the engine here, on the GL thread Media3 calls this from, so the
        // LiteRT delegate is bound to the right thread and EGLContext (see §6.3).
        val engine = SrEngine.create(context.assets, modelAsset)
        return AiUpscaleGlShaderProgram(useHdr, engine, scale, blend)
    }
}
```

```kotlin
class AiUpscaleGlShaderProgram(
    useHdr: Boolean,
    private val engine: SrEngine,
    private val scale: Int,
    private val blend: Float,
) : BaseGlShaderProgram(
        // Real signature: BaseGlShaderProgram(boolean useHighPrecisionColorComponents, int texturePoolCapacity)
        /* useHighPrecisionColorComponents = */ useHdr,
        /* texturePoolCapacity = */ 2,
    ) {

    private var inputW = 0
    private var inputH = 0
    private var readback: ByteBuffer = ByteBuffer.allocateDirect(0)

    // Called before the first frame of a given input size. Returns the OUTPUT size.
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inputW = inputWidth; inputH = inputHeight
        ensureReadback(inputWidth, inputHeight)
        return Size(inputWidth * scale, inputHeight * scale)
    }

    // Called on Media3's GL thread with the input texture already bound.
    // Synchronous by contract — if inference is async, override queueInputFrame
    // (GlObjectsProvider, GlTextureInfo, long) instead and drive outputListener yourself.
    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // HONEST PATH: two copies. This is NOT zero-copy.
        glReadPixels(0, 0, inputW, inputH, GL_RGBA, GL_UNSIGNED_BYTE, readback)  // copy 1: GPU -> CPU
        val out = engine.run(readback, inputW, inputH)                            // CPU -> GPU -> CPU
        val outTex = uploadToPooledTexture(out)                                   // copy 2: CPU -> GPU
        composite(outTex, blend)                                                  // residual against base
        outputListener.onOutputFrameAvailable(outTex, presentationTimeUs)
    }

    override fun release() { engine.close(); super.release() }
}
```

Verified against the Media3 API reference (2026-08-28). Two things an earlier draft of this plan got
wrong and that are worth knowing before you write this class:

- `BaseGlShaderProgram`'s constructor is `(boolean useHighPrecisionColorComponents, int texturePoolCapacity)`.
  It does **not** take a `Context` or a `useHdr` flag — you derive `useHighPrecisionColorComponents` from
  `useHdr` yourself.
- The per-frame hook is `drawFrame(int inputTexId, long presentationTimeUs)`, and `configure(int, int)`
  returns `android.util.Size`, not a `Pair`. `queueInputFrame` takes
  `(GlObjectsProvider, GlTextureInfo, long)` and is the hook to override only for *asynchronous* work.
- `GlTextureProducer` exposes a single method, `releaseOutputTexture(long)`. There is no `width`/`height`
  on it and no `Listener.Releasable`.

The v1 plan's version of this class passed `HardwareBuffer` objects into
`interpreter.runForMultipleInputsOutputs` — which accepts only arrays and `java.nio.Buffer`.

### 6.2 Wiring the chain into both hosts

```kotlin
val chain = listOf(
    // LanczosResample has NO public constructor - static factory only.
    LanczosResample.scaleToFitWithFlexibleOrientation(targetLong, targetShort),
    AiUpscaleEffect(model, scale = 2, blend = 0.7f), // residual, not replacement
    TemporalStabilizeEffect(strength = 0.3f),
    CasSharpenEffect(amount = 0.35f),
)

// Preview — real-time, Tiers 0/1 only in practice
exoPlayer.setVideoEffects(chain.filter { it.isRealtimeSafe })

// Export — note: setVideoEffects() was REMOVED in Media3 1.6.0. Effects go on the EditedMediaItem.
val edited = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
    .setEffects(Effects(/* audioProcessors= */ emptyList(), /* videoEffects= */ chain))
    .build()

Transformer.Builder(context)
    .setVideoMimeType(MimeTypes.VIDEO_H264)   // not H265 — see the note below
    .setAudioMimeType(MimeTypes.AUDIO_AAC)   // audio is preserved; v1 never mentioned it
    .addListener(exportListener)
    .build()
    .start(edited, outputPath)
```

**Two corrections to this block, both verified against the Media3 reference:**

- `setHdrMode` is **not** a method on `Transformer.Builder`. It lives on `Composition.Builder`
  (`Composition.Builder(sequences).setHdrMode(Composition.HDR_MODE_KEEP_HDR)`), so it is
  **unreachable** from the convenient `start(EditedMediaItem, path)` overload — that overload builds
  the `Composition` for you. Reaching it would mean constructing the `Composition` manually.

  Leave the default. The default is `HDR_MODE_KEEP_HDR`, and if HDR editing is unsupported on the
  device Transformer **automatically** falls back to
  `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`, reported through
  `Transformer.Listener.onFallbackApplied`. Forcing the OpenGL constant would **throw on API 24–28**
  — OpenGL tone-mapping needs API 29+, MediaCodec tone-mapping API 31+ — and this project's minSdk
  is 24. The automatic fallback is the correct behaviour and it is already handled.

- `VIDEO_H265` was the plan's choice but is not universally encodable. Phase 1 uses H.264 so the
  first build does not fail on a codec gap; H.265 can be offered later behind a
  `MediaCodecList` capability check.

### 6.3 Threading and context rules (these are the ones that actually bite)

1. **Create the interpreter on the GL thread and invoke it only from there.** The LiteRT GPU delegate
   throws `GpuDelegate must run on the same thread where it was initialized` otherwise.
2. **The EGLContext must be current on the invoking thread** for the delegate's GL path.
   `BaseGlShaderProgram.queueInputFrame` already satisfies this.
3. **Never create a second interpreter per frame.** Create once, reuse; allocate I/O buffers once.
4. **Reuse one set of textures and one PBO** across frames. Per-frame allocation is how you get a
   10-second export and a 10-minute one from the same model.

### 6.4 Service, scheduling and resume

```
WorkManager (schedule, retry, resumability, constraints)
      │  user-initiated → full 6h budget available
      ▼
ForegroundService, type = mediaProcessing (API 35+) | dataSync (API 34 and below)
      │  notification with % + ETA + cancel
      │  implements Service.onTimeout(int, int) → stopSelf() within seconds
      ▼
Transformer export → checkpoint every N frames → fragmented MP4
```

- **Budget tracker:** accumulate `mediaProcessing` runtime; refuse to start a new export past the
  6 h/24 h budget and tell the user when it resets (bringing the app to the foreground resets it).
- **Resume:** write **fragmented MP4** so a partial file is playable, and checkpoint the last committed
  presentation timestamp. On resume, seek to the keyframe at or before it and continue. A plain MP4 has
  its `moov` written at the end, so an interrupted export is otherwise unrecoverable.
- **Chunking (v1 pillar 3) is demoted.** Splitting the *encode* into 5–10 s blocks forces an IDR at every
  boundary (bitrate spikes, visible seams) and requires concatenation. Media3 already streams
  decode→effect→encode incrementally, so memory is bounded without cutting the file. Chunk only if
  measurement shows a real memory problem — and then cut on keyframe boundaries.

### 6.5 Phase 2 — the real zero-copy path (NDK)

```cpp
// LiteRT 2.x CompiledModel API, C++ only.
auto env            = Environment::Create({}).value();
auto compiled_model = CompiledModel::Create(env, "espcn.tflite", kLiteRtHwAcceleratorGpu).value();

// AHardwareBuffer-backed tensor, exposed to GL with no copy:
auto ahwb   = TensorBuffer::CreateManaged(env, kLiteRtTensorBufferTypeAhwb, type, size).value();
auto gl_buf = ahwb.GetGlBuffer().value();            // zero-copy GL view of AHWB memory
auto cl_buf = gl_buf.GetOpenClMemory().value();      // zero-copy OpenCL view

compiled_model.Run({gl_input}, outputs);             // EGL sync fence for ordering, no CPU wait
```

Gate: only start this after Phase 0 proves the two-copy path is too slow on the *target* devices. It is
a C++ project — build system, JNI, ABI splits, and a second code path to test. Do not pay for it
speculatively.

---

## 7. Phase 0 — the feasibility gate (do this first)

v1 opened with Compose UI. That is the wrong order: the whole product rests on one unmeasured number —
**how fast can this model run on a mid-range phone inside a Media3 effect chain?** If the answer is bad,
no amount of UI matters.

One-week spike, throwaway code:

| Measure | Pass condition |
|---|---|
| ESPCN 2× FP16, 640×360 in, on a Snapdragon 7-series + a Mali mid-ranger | ≤20 ms/frame sustained over 600 frames |
| Same, inside `BaseGlShaderProgram.queueInputFrame` (not standalone) | ≤25 ms/frame — measures the two-copy overhead |
| Thermals | <15% throughput drop over a 10-minute sustained run |
| `glReadPixels` + upload round trip alone, 1080p | recorded as the floor the model must beat |
| Tier 0 (Lanczos + CAS) | ≤5 ms/frame at 1080p |

**If the in-pipeline number fails:** ship Tiers 0/1 only, and reframe the product as "smart sharpening"
rather than AI upscaling. That is a viable product. Discovering it in week 1 is cheap; discovering it in
month 4 is fatal.

Also in Phase 0: build the **evaluation harness** — a fixed clip set, PSNR/SSIM/LPIPS against bicubic and
Lanczos references, and a side-by-side player. Without it every later "did the model improve?" argument
is unfalsifiable.

---

## 8. Risk register (replaces v1's single LMK note)

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | In-pipeline inference too slow (two-copy overhead) | **Critical** | Phase 0 gate; ship Tiers 0/1; Phase 2 zero-copy only if justified |
| R2 | Temporal flicker from GAN SR | **Critical** | Tier 3 opt-in + temporal stabilizer; determinism-ordered tiers |
| R3 | 6 h/24 h FGS budget exhausted mid-export | High | `onTimeout()`, budget tracker, fMP4 checkpoint/resume |
| R4 | GPU OOM on large intermediate textures | High | Cap target resolution by RAM class; reuse textures; 4× removed |
| R5 | Thermal throttling after several minutes | High | Phase 0 sustained test; adaptive tier downgrade under thermal pressure |
| R6 | Colour/range shift (BT.601 vs 709, limited vs full) | High | Explicit colour handling + a colour-accuracy test clip in the harness |
| R7 | Delegate init throws on specific GPU drivers | High | `CompatibilityList` + catch-all fallback to Tier 1, per device |
| R8 | Model weights / dataset licensing blocks shipping | High | Verify per model before bundling (§9) |
| R9 | Temp storage exhaustion (multi-GB intermediates) | Medium | Pre-flight free-space check; stream, don't buffer; clean up on cancel |
| R10 | HDR input mishandled | Medium | Tone-map via Media3; HDR output out of scope for v1 |
| R11 | A/V desync at any boundary | Medium | No encode chunking (v1 pillar 3 demoted); A/V sync test in harness |
| R12 | Play policy on background processing / storage permissions | Medium | SAF-only file access; `mediaProcessing` type; no `MANAGE_EXTERNAL_STORAGE` |

---

## 9. Licensing — clear before bundling any weights

Code licences are permissive; **weights are the exposure** and v1 did not mention them.

| Asset | Code licence | Weights | Action |
|---|---|---|---|
| AMD FSR 1.0 (EASU/RCAS) | **MIT** (confirmed, GPUOpen) | n/a (algorithm) | Clear to port; retain notice |
| Fast-SRGAN | **MIT** (confirmed, `HasnainRaz/Fast-SRGAN`) | verify | Check the released checkpoint + its training set |
| ESPCN | implementations MIT; paper code varies | verify | Prefer training your own on a permissively licensed set |

Recommendation: **train ESPCN yourself** on a clearly licensed corpus (DIV800 / DIV2K-Flickr2K licensing
must be checked per use) and ship your own FP16 weights. It removes the licensing question entirely, costs
little (ESPCN trains in hours), and gives you a model tuned to real phone video rather than DIV2K crops.
Record every model's provenance in a `MODEL_CARD.md`.

---

## 10. Delivery phases

Each phase has an exit gate. Do not start the next one on an unmet gate.

### Status as of 2026-08-28

| Phase | State | Gate |
|---|---|---|
| 0 | ⚠️ harness built (`tools/eval/quality.py`, self-tested), **never run on a device** | Unmet — no hardware. No latency figure here is a measurement. |
| 1 | ✅ implemented (Compose UI, SAF, preview, export) | Unmet — **never compiled**. No JDK in the sandbox. |
| 2 | ⚠️ code + a real 56 KB ESPCN `.tflite` complete, **unverified on device** | **FAILING** — see `MODEL_CARD.md`. ESPCN 35.01 dB vs bicubic 40.12 dB on the synthetic set. |
| 3 | ✅ `ExportService` with `mediaProcessing` FGS, `onTimeout`, ETA, cancel | Untested. |
| 4 | ⚠️ `CompareSlider` built with range-slider semantics; temporal stabilizer **not built** | Unmet. |
| 5 | ❌ | Needs the device matrix. |
| 6 | ❌ deliberately not started | Deferred below, pending Phase 0/2 evidence. |

The Phase 2 result deserves care. Training a model from scratch moved it from 26.97 dB to 35.01 dB,
which proves training, TFLite conversion, and the runtime all work. What it does **not** prove is
quality: the synthetic test set is piecewise-smooth, which is near bicubic's best case, so there is
almost no headroom. Real imagery is the only meaningful test, and it has not been run.

**Phase 0 — Feasibility & measurement (1–2 weeks)**
- Benchmark harness + evaluation clip set + PSNR/SSIM/LPIPS pipeline
- Measure Tier 0/1/2 per §7
- Decide: full AI product, or sharpening product
- *Gate:* in-pipeline ESPCN ≤25 ms/frame on two mid-range devices, sustained

**Phase 1 — Pipeline & Tiers 0/1 (2–3 weeks)**
- Compose shell, SAF picker, Media3 ExoPlayer preview
- `LanczosResample` + CAS/FSR shader port as custom `GlEffect`
- Export via Transformer with **audio preserved**, rotation correct, H.265
- *Gate:* 60 s clip exported, audio in sync, colours match source, no crash on 3 devices

**Phase 2 — LiteRT effect integration (3–4 weeks)**
- `AiUpscaleGlEffect` + `BaseGlShaderProgram`, FP16, two-copy path
- Capability detection + tier fallback
- Threading/EGL rules enforced (§6.3)
- *Gate:* export quality beats Lanczos reference on the harness clips at a measured frame cost

**Phase 3 — Reliability & long exports (2–3 weeks)**
- `mediaProcessing` FGS, `onTimeout()`, 6 h budget tracker
- fMP4 checkpoint + resume; cancel; progress + ETA
- Thermal downgrade
- *Gate:* 30-minute export completes; kill-and-resume recovers; 6 h budget path tested via
  `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS`

**Phase 4 — Quality & UX (2–3 weeks)**
- Temporal stabilizer; residual blend control; Tier 3 opt-in with warning
- A/B comparison slider (Compose canvas) — preview and export share the chain, so it is honest
- *Gate:* flicker rated acceptable blind on the harness clips

**Phase 5 — Hardening & release**
- Device matrix (Adreno high/mid, Mali high/mid, one Tensor), Android 12→16
- Crash-free target, battery/thermal report, size budget (Play Asset Delivery if models push the APK up)
- *Gate:* zero crashes across the matrix on a 10-export soak

**Deferred:** Phase 6 — zero-copy NDK path (§6.5), only on evidence from Phase 0/2.

---

## 11. What was deliberately kept from v1

- On-device only, no cloud. Correct and differentiating — keep it as the headline.
- Never decode frames into `Bitmap`. Correct instinct; the *mechanism* was wrong (§1.1), not the goal.
- Adaptive capability-based fallback. Correct; strengthened with determinism-ordered tiers.
- Foreground service + notification. Correct; corrected to `mediaProcessing` + budget handling.
- Comparison slider UX. Correct; now trustworthy because preview and export share one effect chain.

---

## 12. Open questions

1. Is the Play-services LiteRT runtime acceptable, or must the delegate be bundled for non-Play devices?
2. Ship models in the APK or via Play Asset Delivery? Depends on final model sizes vs the 200 MB base.
3. Is 1080p the hard ceiling for v1, or is 1440p expected? Drives the memory budget materially.
4. Monetisation model — affects whether Tier 3 is a paid tier and how models are delivered.
5. Free vs paid storage: is a temp-space pre-flight check enough, or do we need user-visible storage UI?

---

## Appendix A — Verification status

Every claim about the outside world above was checked on 2026-08-28 against vendor documentation and
release metadata. What has **not** been verified:

- **No code in this repository has been compiled or executed.** The sandbox has no JDK, Gradle, or
  Android SDK, so §6 is design, not tested code.
- **No latency figure is a measurement.** All are Phase 0 targets.
- **Weight/dataset licences are unconfirmed** for ESPCN and Fast-SRGAN checkpoints (§9).
- Repository contents at the time of writing: `README.md` and `docs/` only — no source, no build files.

## Appendix B — Sources

- Media3 releases (1.11.0 stable, 2026-08-05): https://developer.android.com/jetpack/androidx/releases/media3
- Media3 release notes (1.6.0 removal of `Transformer.Builder.setVideoEffects`): https://raw.githubusercontent.com/androidx/media/master/RELEASENOTES.md
- Media3 custom effects / `GlShaderProgram.queueInputFrame` + MediaPipe example: https://developer.android.com/media/implement/editing-app
- `GlEffect` interface: https://github.com/androidx/media/blob/release/libraries/effect/src/main/java/androidx/media3/effect/GlEffect.java
- Transformer getting started (`EditedMediaItem`, audio mime type, HDR mode): https://developer.android.com/media/media3/transformer/getting-started
- LiteRT Android versions (2.2.0 / 1.4.2, minSdk, NDK): https://developers.google.com/edge/litert/android
- LiteRT GPU delegate, Interpreter API: https://ai.google.dev/edge/litert/android/gpu
- LiteRT GPU, C/C++ API (EGLContext requirement): https://ai.google.dev/edge/litert/android/gpu_native
- LiteRT CompiledModel zero-copy (`CreateFromGlBuffer`, AHWB): https://developers.google.com/edge/litert/next/gpu and https://developers.google.com/edge/litert/next/cpp
- `Interpreter` / `InterpreterApi` accepted input types: https://ai.google.dev/edge/api/tflite/java/org/tensorflow/lite/Interpreter
- NNAPI deprecation (Android 15): https://developer.android.com/ndk/guides/neuralnetworks
- Foreground service timeouts, `mediaProcessing`, `onTimeout`, 6 h/24 h: https://developer.android.com/develop/background-work/services/fgs/timeout
- Background task options (`mediaProcessing` for transcoding): https://developer.android.com/about/versions/15/changes/datasync-migration
- AMD FSR 1.0, MIT licence: https://gpuopen.com/fidelityfx-superresolution/
- Fast-SRGAN, MIT licence: https://github.com/HasnainRaz/Fast-SRGAN
- Temporal flicker from frame-independent SR: https://www.sciencedirect.com/science/article/abs/pii/S0952197623019735
