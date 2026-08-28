# 09 — Testing Strategy

Goal: an image app where "it worked on my photo" is not the test plan.
Companion to `04-IMAGE-PIPELINE.md` (traps) and `10-RELEASE-CI-CD.md`.

---

## 1. Test pyramid

```
        ┌───────────┐ E2E (UIAutomator, 3 flows) + manual device lab
       ┌─────────────┐ Screenshot/snapshot (Compose, Roborazzi) + golden images
      ┌───────────────┐ Instrumented (pipeline on device/emulator: decode→encode, bench)
     ┌─────────────────┐ JVM unit (viewmodels, tile math, EXIF, model manager, billing logic)
```
Rough ratio 70 / 20 / 10. Coverage gate: `:core:media`, `:ml:*`, `:core:data` ≥ 80% lines;
features ≥ 60% (UI covered by snapshots instead).

## 2. Unit-test focus areas (JVM + Robolectric)

| Area | Example cases |
|---|---|
| Tile grid math | exact grid for W×H∈{1×1, 256×256, 1000×1000, 10000×1000, non-multiples}; overlap never < 16; last row/col covered |
| Blend weights | cosine ramp sums to 1 at every seam pixel; seam-free synthetic gradient test |
| MemoryBudget | 12 MP on 2 GB heap → derate order deterministic; never exceeds cap |
| EXIF round-trip | orientation variants (1–8), GPS strip, date preserved, Software tag set |
| Filename collisions | `(1)`, `(2)`, unicode names, 255-char limit |
| Model manager | corrupt sha256 → reject; partial download → resume; version swap atomic |
| Entitlements | daily cap boundary, offline restore, refund revocation |
| DeviceTier | RAM/SoC fixtures → tier mapping table |

## 3. Instrumented tests (real pipeline)

- Golden corpus (below) through the full pipeline on emulator (x86_64) + 1 physical device per tier in nightly CI (Firebase Test Lab).
- Cancellation mid-tile leaves no partial files; OOM injection (huge bitmap) → typed `OomRiskError`, no crash.
- Foreground service: process death → WorkManager resumes queue exactly once (idempotency keys).
- Face pipeline: 0, 1, 8, >8 faces; tiny face (20 px) skipped gracefully; stylized face with anime preset → stage skipped.

## 4. Golden-image corpus & quality gates (the ML contract)

- `testdata/golden/`: **50 fixed inputs** — portraits (varied skin tones/ages), group shots, anime, line art, landscapes, text documents, night/noise, JPEG-artifacted, scans with dust, panorama, extreme aspect. All CC0/self-captured. Never user data.
- For each model version × precision: run once → store reference outputs in LFS; CI recomputes and enforces:
  - fp16 vs fp32: SSIM ≥ 0.995
  - int8 vs fp32: SSIM ≥ 0.98 + 20-image human eyeball pass recorded in model card
  - app pipeline vs model-direct: SSIM ≥ 0.999 (catches blending/EXIF regressions)
- SSIM implemented in a small Kotlin tool (or Python eval harness mirrored from `tools/`).
- Any intentional model change bumps model semver + regenerates references (documented in model card).

## 5. UI & snapshot tests

- Roborazzi/Paparazzi screenshots: every screen × {light,dark} × {en,ar(RTL)} × font-scale {1.0, 2.0} — one PR-diffable gallery.
- Compose UI tests: compare slider a11y actions, config sheet state, queue list states (running/failed/retry), paywall triggers (entitlement mocked).
- `TestHarness` fakes: `FakeUpscaleEngine` (deterministic 2× bicubic — fast UI tests), `FakeBilling`, `FakeModelRepository`.

## 6. CI jobs (GitHub Actions — details in doc 10)

| Job | Trigger | Contents |
|---|---|---|
| `checks` | every PR | ktlint, detekt, unit, Roborazzi diff, assemble debug, R8 release smoke |
| `instrumented` | nightly + release branch | x86_64 emulator pipeline+golden (API 26 & latest) |
| `device-lab` | release branch | Firebase Test Lab: 6-device matrix (doc 14 §3) incl. golden corpus subset |
| `macrobench` | nightly main | startup + jank regressions > 10% flag |
| `native-sanitize` | weekly | ASan/TSan build runs pipeline test |
| `model-quality` | on models repo change | conversion + SSIM gates + license-list check |

## 7. Manual QA checklist (pre-release, per doc 10 §5)

- ☐ 20-photo marathon on each lab tier device (mixed formats incl. HEIC, 48 MP input, 800-px meme)
- ☐ Airplane mode full pass (enhance + save + share) — proves the privacy promise
- ☐ Interrupt matrix: call mid-job, screen off, force-stop, storage-full, 1% battery saver
- ☐ Share-intent from WhatsApp/Gallery/Files/Drive-cached
- ☐ Upgrade path: install over previous version — settings, models, entitlements survive
- ☐ TalkBack pass on enhancement loop; 200% font; dark mode screenshots reviewed
- ☐ Play pre-launch report clean; Data Safety re-checked

## 8. Bug triage rules

- Sev-1 (crash/data-loss/wrong-output-saved): hotfix track, rollout pause (doc 10 §7).
- Quality complaints ("face looks weird") → attach anonymized tier+model info from Diagnostics; feed golden corpus candidates — never ask users to send photos (promise!); add *similar* CC0 stand-in to corpus instead.

## 9. Beta program mechanics

- Closed beta (Phase 8): in-app feedback form (text + optional *result* screenshot the user explicitly attaches) + Crashlytics.
- Staged rollout is the final test: 5% → watch 48 h Vitals (crash, ANR, uninstall) → ladder (doc 10 §6).
