# PixelRevive (working name) — On-Device AI Photo Upscaler & Restorer for Android

An Android app that upscales (2×/4×) and restores photos — denoise, deblur, face restore —
**entirely on the device**. No upload, no account, works in airplane mode.
*(Video upscaling is the v2 vision — see `docs/plans/15-FUTURE-ROADMAP.md`.)*

## 📋 Complete A–Z plan pack (`docs/plans/`)

| # | File | Covers |
|---|---|---|
| 00 | [00-MASTER-PLAN.md](docs/plans/00-MASTER-PLAN.md) | Vision, goals, scope, glossary, decision log — **start here** |
| 01 | [01-PRODUCT-REQUIREMENTS.md](docs/plans/01-PRODUCT-REQUIREMENTS.md) | Personas, user stories + acceptance criteria, feature matrix |
| 02 | [02-TECHNICAL-ARCHITECTURE.md](docs/plans/02-TECHNICAL-ARCHITECTURE.md) | Stack, modules, data flow, threading, native bridge |
| 03 | [03-AI-ML-MODELS.md](docs/plans/03-AI-ML-MODELS.md) | Model zoo, runtimes (ncnn/ONNX/LiteRT), quantization, tiling, licenses |
| 04 | [04-IMAGE-PIPELINE.md](docs/plans/04-IMAGE-PIPELINE.md) | Decode→tiles→merge→encode, EXIF, memory guardrails |
| 05 | [05-UI-UX-DESIGN.md](docs/plans/05-UI-UX-DESIGN.md) | Every screen spec'd, compare slider, design system, a11y |
| 06 | [06-PROJECT-SETUP.md](docs/plans/06-PROJECT-SETUP.md) | Repo layout, Gradle, dependencies, conventions |
| 07 | [07-DEVELOPMENT-ROADMAP.md](docs/plans/07-DEVELOPMENT-ROADMAP.md) | Phase 0→9, 25-week plan, milestones, cut-lines |
| 08 | [08-PERFORMANCE-OPTIMIZATION.md](docs/plans/08-PERFORMANCE-OPTIMIZATION.md) | Budgets, device tiers, Vulkan/GPU delegates, thermals |
| 09 | [09-TESTING-STRATEGY.md](docs/plans/09-TESTING-STRATEGY.md) | Golden-image SSIM gates, snapshots, device lab, CI jobs |
| 10 | [10-RELEASE-CI-CD.md](docs/plans/10-RELEASE-CI-CD.md) | Signing, Play tracks, staged rollout, ops runbook |
| 11 | [11-PRIVACY-SECURITY.md](docs/plans/11-PRIVACY-SECURITY.md) | Data-flow proof, permissions, Data Safety, security checklist |
| 12 | [12-MONETIZATION-GROWTH.md](docs/plans/12-MONETIZATION-GROWTH.md) | Free/Pro matrix, pricing, paywall UX, ASO, launch channels |
| 13 | [13-RISKS-CONTINGENCIES.md](docs/plans/13-RISKS-CONTINGENCIES.md) | Risk register with triggers and contingencies |
| 14 | [14-BUDGET-RESOURCES.md](docs/plans/14-BUDGET-RESOURCES.md) | Team scenarios, device lab, costs, funding |
| 15 | [15-FUTURE-ROADMAP.md](docs/plans/15-FUTURE-ROADMAP.md) | v1.x themes, video upscaling (v2), iOS, research watch-list |

## Quick facts

- **Stack:** Kotlin, Jetpack Compose (M3), Hilt, WorkManager, C++/JNI, ONNX Runtime *or* ncnn (benchmark-gated), ML Kit faces
- **Models:** Real-ESRGAN family + NAFNet + face restoration — with a hard license-audit gate (doc 03 §7)
- **minSdk:** 26 (Android 8.0) · arm64-v8a · 16 KB-page compliant
- **Privacy promise:** photos never leave the device — verifiable (doc 11 §1)
- **Plan:** 25 weeks to launch, phase-gated (doc 07)

## Status

- [x] Planning pack complete
- [ ] Phase 0 — discovery & setup
- [ ] Phase 1 — foundations
- [ ] … → see roadmap
