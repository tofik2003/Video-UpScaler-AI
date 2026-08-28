# Video UpScaler AI

On-device video enhancement for Android. No cloud, no upload — frames never leave the device.

**Honest headline:** this is currently a *Lanczos upscaler with an ESPCN hook wired up and a
development model attached*. It is not yet a good AI upscaler. See the phase table below.

## Phase status

| Phase | Status | Gate |
|---|---|---|
| 0 — Feasibility & measurement | ⚠️ **harness built, not run** | Needs real hardware. No latency figure in this repo is a measurement. |
| 1 — Pipeline & Tiers 0/1 | ✅ implemented | **Never compiled.** No JDK in the sandbox. |
| 2 — LiteRT effect integration | ⚠️ **code + model complete, unverified** | Quality gate currently FAILS (see below). |
| 3 — Reliability & long exports | ✅ implemented | Untested. |
| 4 — Quality & UX | ⚠️ partial | Compare slider built but not wired to a result screen. |
| 5 — Hardening & release | ❌ | Needs the device matrix. |
| 6 — Zero-copy NDK | ❌ deliberately not started | PLAN.md defers it pending Phase 0/2 evidence. |

**The two numbers that matter:**

- Nothing here has ever been compiled. There is no JDK, Gradle, or Android SDK in the environment
  where this was written.
- The bundled model was trained on **synthetic images** and currently scores *below* the bicubic
  reference on the quality gate. It exists to make the Phase 2 plumbing testable, not to ship.
  `MODEL_CARD.md` has the details and the command to train a real one.

## Layout

```
app/src/main/java/com/videoupscaler/ai/
  MainActivity.kt                Compose host + SAF picker
  UpScalerApp.kt                 process-scoped exporter
  ai/UpscaleEngine.kt            LiteRT interpreter, GPU delegate + CPU fallback
  ai/AiUpscaleEffect.kt          GlEffect + the glReadPixels round trip
  pipeline/UpscaleChain.kt       the one effect list both preview and export use
  pipeline/VideoExporter.kt      Transformer export, progress, pre-flight
  service/ExportService.kt       mediaProcessing FGS, 6h budget handling
  ui/…                           Compose UI, Material 3

models/espcn/espcn.py            ESPCN definition, training, TFLite export
tools/eval/quality.py            PSNR/SSIM, self-tested, pure numpy
docs/PLAN.md                     the plan, with corrections
docs/DESIGN.md                   product and UI design
docs/BUILD_AND_RUN.md            build, device choice, CI
```

## The load-bearing design decision

`pipeline/UpscaleChain.kt` builds **one** effect list, and both hosts consume it —
`ExoPlayer.setVideoEffects` for preview, `EditedMediaItem.setEffects` for export. That is the only
reason the preview cannot silently disagree with the export. Any change that gives the two hosts
separate lists reintroduces the bug `docs/DESIGN.md` §2 exists to prevent.

## Building

CI is **not enabled** — the GitHub App token lacks the `workflows` permission. Copy
`docs/ci-workflow.yml` to `.github/workflows/android.yml` yourself; the exact commands are in
`docs/BUILD_AND_RUN.md`.

## Tooling

```bash
pip install -r tools/requirements.txt
python3 tools/eval/quality.py                      # self-test the metrics
python3 models/espcn/espcn.py eval --model models/espcn/espcn_x2.tflite
```
