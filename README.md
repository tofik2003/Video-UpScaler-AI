# Video-UpScaler-AI

On-device AI video enhancement engine for Android. No cloud, no upload — enhancement runs entirely on
the device.

**Status: planning.** No implementation exists yet.

## Documentation

- [`docs/PLAN.md`](docs/PLAN.md) — engineering plan (v2, revised). Section 1 records what changed from
  the original plan and why; §7 is the Phase 0 feasibility gate that must pass before implementation is
  committed to.
- [`docs/BUILD_AND_RUN.md`](docs/BUILD_AND_RUN.md) — **how to actually get this onto a device.**
  Toolchain versions, device setup, the runnable subset, and first-run failures. Read this before
  touching code.
- [`docs/DESIGN.md`](docs/DESIGN.md) — app design plan: UX, screens, states, visual system. Read this
  before writing any UI. §2 covers the preview-vs-export problem that shapes the whole interface.
- [`docs/plan.json`](docs/plan.json) — the engineering plan in structured form, for tooling and tracking.

## Current state

**Phase 1 is implemented but has never been compiled.** The app can pick a video via SAF, preview it
through the effect chain, and export it upscaled with audio intact. UI is Jetpack Compose / Material 3.

- `pipeline/UpscaleChain.kt` — the shared effect chain (Tier 0: Lanczos resample)
- `pipeline/VideoExporter.kt` — Transformer export with progress, cancel, storage pre-flight
- `ui/EnhanceScreen.kt`, `ui/EnhanceViewModel.kt`, `ui/theme/Theme.kt` — Compose UI
- `UpScalerApp.kt` — process-scoped exporter so rotation does not cancel a running export

Still missing: CI is not enabled (the GitHub App token lacks the `workflows` permission), so the build
has never run. And there is no `.tflite` model, so the AI tiers do not exist yet — this is a Lanczos
upscaler, not an AI one. `docs/BUILD_AND_RUN.md` §0 has the full inventory.

## Key constraints worth knowing up front

- Enhancement is a shared list of Media3 `GlEffect` stages: `ExoPlayer.setVideoEffects()` hosts it for
  real-time preview, `EditedMediaItem.setEffects()` hosts it for offline export. One chain, so preview
  and export cannot drift visually.
- Phase 1 inference crosses a **two-copy** bridge into the LiteRT GPU delegate. It is not zero-copy.
  True zero-copy (LiteRT C++ `CompiledModel` + `AHardwareBuffer`) is a deferred Phase 2 objective.
- Tiers are ordered by *temporal determinism*, not sharpness: GAN super-resolution shimmers when run
  per frame, so it is opt-in and stabilized rather than the default.
- Long exports run in a `mediaProcessing` foreground service, which is capped at 6 hours per 24-hour
  period. Checkpoint and resume are requirements, not features.
