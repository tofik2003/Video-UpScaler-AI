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
- [`docs/plan.json`](docs/plan.json) — the same plan in structured form, for tooling and tracking.

## Current state

A **Phase 1 skeleton** exists — Gradle build files, manifest, and a `MainActivity` hosting a
`PlayerView`, with Media3 1.11.0 and LiteRT 1.4.2 declared. It has **never been compiled**: this
environment has no JDK or Android SDK, and the CI workflow could not be committed because the GitHub
App token lacks the `workflows` permission.

Also missing: the effect chain, the export path, and any `.tflite` model — so the AI upscaling does not
work yet. `docs/BUILD_AND_RUN.md` §1a explains how to enable CI and get a build; §0 lists exactly what
is and is not present.

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
