# 00 — MASTER PLAN (Start Here)

**Project:** Android Image Upscaler & Restorer — 100% on-device AI
**Working app name:** PixelRevive *(placeholder — run a trademark check before launch, see doc 12)*
**Package:** `com.pixelrevive.app` *(placeholder)*
**Repo:** `Video-UpScaler-AI` (image app first; video upscaling is the v2 vision — see doc 15)
**Last updated:** 2026-08-28

---

## 1. Elevator pitch

PixelRevive is an Android app that makes old, blurry, small, or damaged photos look
sharp and clean — **entirely on the device**. No upload, no account, no waiting on a
server. Photos are upscaled 2×/4×, denoised, deblurred, and (for faces) restored using
compact neural networks that run on the phone's own CPU/GPU/NPU. Works in airplane mode.

**One-line value proposition:** *"Your photos never leave your phone — and they come back beautiful."*

## 2. Why this app can win

| Competitor | Weakness we exploit |
|---|---|
| Remini, PhotoApp, EnhanceFox | Cloud-based: subscription-gated, slow on bad networks, serious privacy concerns, per-week limits |
| Most "AI enhancer" apps | Aggressive ads/paywalls, upload images to unknown servers |
| **PixelRevive** | Offline-first, privacy-absolute, one-time-purchase option, no daily credit limits |

Real, growing demand: old-photo restoration, marketplace sellers (eBay/Vinted/Depop),
social creators, printing old family photos, anime/webtoon fans.

## 3. Goals & success metrics

### Launch goals (v1.0)
- Ship to Google Play internal → closed beta → production within **~6 months** (solo/small team).
- Core loop: pick photo → enhance (2×/4× + denoise + face restore) → compare → save. Done in **≤ 3 taps**.
- Mid-range device (e.g. 6 GB RAM, Snapdragon 6/7-series): 12 MP → 48 MP in **≤ 45 s**; flagship **≤ 15 s**.
- Crash-free sessions ≥ **99.5%**; ANR rate < **0.47%** (Play bad-behavior threshold).
- App size: base download **≤ 60 MB**; extra models via on-demand download.

### 12-month business goals
- 100 K+ installs, 4.4★+ rating, conversion to paid ≥ 2–3% of MAU.
- Zero image-privacy incidents **by design** (no image ever leaves the device).

### Non-goals for v1 (explicitly out of scope)
- ❌ Cloud/GPU-server enhancement (possible opt-in later, doc 15)
- ❌ Video upscaling (v2 — the repo's namesake, doc 15)
- ❌ Generative fill / object removal / relighting
- ❌ iOS / desktop (later)
- ❌ Social features, accounts, cloud sync

## 4. What "A–Z" means here — the planning pack

All plans live in `docs/plans/`. Read in order; each file is self-contained.

| # | File | What it covers |
|---|---|---|
| 00 | `00-MASTER-PLAN.md` | Vision, goals, scope, how to use these docs, glossary, decision log |
| 01 | `01-PRODUCT-REQUIREMENTS.md` | Personas, jobs-to-be-done, user stories w/ acceptance criteria, feature matrix P0/P1/P2, competitive analysis |
| 02 | `02-TECHNICAL-ARCHITECTURE.md` | Tech stack, module map, layers, key data flows, state, DI, error policy |
| 03 | `03-AI-ML-MODELS.md` | Model zoo (SR/restore/faces), runtime comparison (ncnn / ONNX Runtime / LiteRT), quantization, tiling, **license audit**, model delivery |
| 04 | `04-IMAGE-PIPELINE.md` | Decode → preprocess → tiles → inference → merge → encode, EXIF/color/MediaStore, OOM guardrails |
| 05 | `05-UI-UX-DESIGN.md` | Sitemap, every screen spec'd, before/after compare slider, design system, accessibility, localization |
| 06 | `06-PROJECT-SETUP.md` | Repo layout, Gradle/version catalog, dependencies, code style, Git conventions, Play Asset Delivery config |
| 07 | `07-DEVELOPMENT-ROADMAP.md` | Phase 0→9, weekly plan, milestones, exit criteria, MVP cut lines |
| 08 | `08-PERFORMANCE-OPTIMIZATION.md` | Device tiers, delegates (Vulkan/GPU/XNNPACK), thermal management, budgets, benchmark harness |
| 09 | `09-TESTING-STRATEGY.md` | Test pyramid, golden-image SSIM tests, Compose/snapshot tests, device matrix, CI |
| 10 | `10-RELEASE-CI-CD.md` | Signing, versioning, GitHub Actions, Play tracks, staged rollout, monitoring |
| 11 | `11-PRIVACY-SECURITY.md` | Data-flow diagram, permissions, Play Data Safety answers, GDPR, security checklist, Play AI policy |
| 12 | `12-MONETIZATION-GROWTH.md` | Pricing model, free-vs-pro matrix, Play Billing, ASO, launch marketing, retention |
| 13 | `13-RISKS-CONTINGENCIES.md` | Risk register w/ probability × impact × mitigation, kill-switch triggers |
| 14 | `14-BUDGET-RESOURCES.md` | Team scenarios, device lab, services cost, total lean vs funded budget |
| 15 | `15-FUTURE-ROADMAP.md` | v1.x themes, video upscaling (v2), iOS, cloud-hybrid, RAW support |

### Suggested reading order by role
- **Solo founder / everything:** 00 → 01 → 07 → 03 → 02 → rest as needed
- **Android dev:** 02 → 04 → 06 → 03 → 08 → 09
- **ML engineer:** 03 → 04 → 08 → 09
- **Designer:** 05 → 01
- **Business:** 00 → 12 → 14 → 13

## 5. Core product loop (the thing we must nail)

```
 Pick image(s)          Configure              Process              Enjoy
┌────────────┐   ┌──────────────────┐   ┌─────────────────┐   ┌─────────────┐
│ Photo picker│ → │ Scale 2×/4×     │ → │ Tiled on-device │ → │ Before/after│
│ or gallery  │   │ Model: Photo/   │   │ inference w/    │   │ slider      │
│ (recent/    │   │ Anime/Face fix  │   │ progress + can- │   │ Save to     │
│  albums)    │   │ Denoise slider  │   │ cel + queue     │   │ Gallery/    │
│             │   │ Output format   │   │                 │   │ share       │
└────────────┘   └──────────────────┘   └─────────────────┘   └─────────────┘
```

Everything else (paywall, settings, batch, benchmarks) is secondary to making this loop
fast, obvious, and delightful.

## 6. Glossary

| Term | Meaning |
|---|---|
| SR / Super-resolution | Neural upscaling: increasing resolution while synthesizing plausible detail |
| Restoration | Denoise, deblur, de_scratch, JPEG-artifact removal, face restoration, colorize |
| Tile / patch | Model input size (e.g. 128×128 → 512×512); big images processed tile-by-tile with overlap blending |
| Overlap blend | Neighboring tiles share a border (e.g. 16 px) merged with a feathered/cosine ramp to hide seams |
| fp16 / int8 | Numeric precision of model weights; fp16 ~visual-lossless, int8 smaller/faster but can artifact |
| NN delegate | Acceleration backend: Vulkan GPU, OpenGL GPU, XNNPACK (CPU), QNN (Qualcomm NPU/DSP) |
| NNAPI | Android's old NPU bridge — **deprecated** (Android 15+); do not build on it |
| LiteRT | New name of TensorFlow Lite (renamed 2024) |
| ncnn | Tencent's mobile-first inference framework with excellent Vulkan GPU support |
| ONNX Runtime | Microsoft's cross-platform inference engine, good mobile support (ONNX Runtime Mobile) |
| GFPGAN / CodeFormer | Face-restoration networks; CodeFormer is **non-commercial** licensed — see doc 03 §7 |
| OOM | Out-of-memory crash; the #1 engineering risk for large-image processing |
| PAD | Play Asset Delivery — ship models as on-demand asset packs instead of bloating the APK |
| Data Safety | Play Console form describing data collection — our answer is "none" |
| AAB | Android App Bundle — required upload format for Play |
| 16 KB pages | New Android memory-page alignment requirement for native libs (mandatory since Nov 2025) |
| SSIM / PSNR | Image-similarity metrics used in golden tests |

## 7. Decision log (append-only; never delete rows)

| ID | Date | Decision | Rationale | Reversible? |
|---|---|---|---|---|
| D-001 | 2026-08-28 | Kotlin + Jetpack Compose single-Activity app | Modern default, smallest long-term cost | Hard |
| D-002 | 2026-08-28 | Primary inference: evaluate ONNX Runtime Mobile vs ncnn; ship whichever wins the doc-08 benchmark | Both support Vulkan/GPU + fp16; decision gate at Phase 2 | Medium |
| D-003 | 2026-08-28 | Models ship as on-demand downloads (PAD + CDN), one compact model bundled | Keeps base app small for ASO conversion | Medium |
| D-004 | 2026-08-28 | No image ever leaves the device — non-negotiable product promise | Core differentiation + simplest privacy story | Brand promise |
| D-005 | 2026-08-28 | Monetization: freemium with one-time "Lifetime Pro" + subscription options | Removes #1 complaint about competitors (forced subs) | Easy |
| D-006 | 2026-08-28 | minSdk 26 (Android 8.0), targetSdk = latest required by Play | ~97%+ device coverage, Play compliance | Hard-ish |
| D-007 | 2026-08-28 | Face detection via ML Kit (on-device, free); restoration network decided in doc 03 | Battle-tested, no Play service drama | Easy |

## 8. How to use this pack

1. Read 00 (this file) and 01. Adjust scope to your team size (doc 14).
2. Work the roadmap (07) top-down; each phase links to the detailed doc you need.
3. Before every phase gate, re-read doc 13 (risks) for that phase's watch-items.
4. Keep the decision log growing — future-you will thank present-you.
