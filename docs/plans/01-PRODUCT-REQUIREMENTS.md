# 01 — Product Requirements (PRD)

Companion to `00-MASTER-PLAN.md`. Everything here is written to be testable.

---

## 1. Target users & personas

### P1 — "Maya", casual memory keeper (PRIMARY)
- 38, mid-range Samsung/Redmi, 1000s of WhatsApp-compressed photos and old scans
- **Job:** "Make this blurry photo of my grandparents good enough to print/post."
- Needs: 1-tap enhancement, no jargon, trusts the app with private photos
- Success: result clearly better in 30 s, saved without hunting for folders

### P2 — "Dev", marketplace seller
- 27, resells on Vinted/eBay; photos shot quickly, sometimes dim/grainy
- **Job:** "Batch-fix 20 product photos so listings look professional."
- Needs: batch queue, consistent output, fast turnaround

### P3 — "Kenji", anime/webtoon fan
- 21, flagship phone, wallpapers & fan art
- **Job:** "Upscale this 800 px art to my 1440p screen — keep lines crisp."
- Needs: dedicated anime model, 4×, large-output support

### P4 — "Amara", family archivist
- 55, scanning 1960s–90s prints; faded, scratched, sepia
- **Job:** "Restore and maybe colorize these scanned family photos."
- Needs: scratch/fade handling, face restore, gentle defaults, big buttons

## 2. Jobs-to-be-done (JTBD) summary

| # | When… | I want to… | So that… |
|---|---|---|---|
| JTBD-1 | I have a small/blurry photo | upscale it 2×/4× with AI | it looks sharp on a big screen/print |
| JTBD-2 | I have a noisy/dark photo | denoise & sharpen it | it looks clean and professional |
| JTBD-3 | I have old photos with faces | restore the faces | people are recognizable again |
| JTBD-4 | I have many photos | queue them all | I don't babysit each one |
| JTBD-5 | I care about privacy | keep photos on-device | nobody can leak them |
| JTBD-6 | I have a scratched print | remove scratches/damage (v1.1) | the scan looks preserved |

## 3. User stories & acceptance criteria

### Import & viewing
- **US-001** As a user, I can pick an image from Gallery, Files, or any app via *Share*.
  - AC: Android Photo Picker supported; SAF file picker supported; `ACTION_SEND`/`ACTION_VIEW` intent filters for image/*; JPEG/PNG/WebP/HEIF/AVIF(A12+) decode.
- **US-002** As a user, I see a full-screen image with a **before/after compare slider** (drag handle; 2-finger press-and-hold toggles original).
  - AC: slider is sub-pixel smooth (no jank > 1 frame), works in zoom/pan mode, TalkBack labels both sides.
- **US-003** As a user, I can zoom to 100%+ (pinch) on the result to inspect detail.

### Enhancement
- **US-010** As a user, I can choose **scale: 2× / 4×** (device tier may hide 4×, doc 08).
- **US-011** As a user, I can choose a **model preset**: *Auto* / Photo / Anime·Art / Face+Photo / Old-photo.
  - AC: *Auto* inspects EXIF + content heuristics (face count, edge style) and picks; choice persists.
- **US-012** As a user, I can set **denoise/deblur strength**: Off / Low / Medium / Strong (maps to pre-filter + model variant, doc 03 §5).
- **US-013** As a user, I see honest **progress**: tile X/Y %, elapsed + ETA, cancel button; app stays responsive.
  - AC: cancel ≤ 300 ms; progress updates ≥ 2 Hz; no ANR (all inference off main thread).
- **US-014** As a user with many faces in a photo, faces are detected and restored (doc 03 §6).
  - AC: up to 8 faces; each ≤ 512 px crop; failures fall back gracefully to whole-image SR.
- **US-015** As a user, I can queue **multiple images** (batch) processed sequentially in a foreground service.
  - AC: survives app backgrounding; notification shows queue progress; per-item retry on failure.

### Output
- **US-020** As a user, I choose output format (JPEG 95 / JPEG 100 / PNG / HEIF-Q90 / WebP-95) and location (default `Pictures/PixelRevive/` via MediaStore, or SAF folder).
  - AC: no duplicates (name collision → ` (1)` suffix); MediaStore scan triggered; EXIF date/camera preserved, **GPS stripped by default** with a setting.
- **US-021** As a user, I can save, share, or set-as-wallpaper directly from the result screen.
- **US-022** As a user, I keep the original untouched — app never overwrites source images. (Hard rule.)

### Trust & transparency
- **US-030** First run: 3-screen onboarding states photos **never leave the device**; "Try in airplane mode" is a supported demo.
- **US-031** A Settings → *Diagnostics* screen shows device tier, chosen runtime/delegate, last benchmark, model versions.

### Monetization (see doc 12)
- **US-040** Free tier: unlimited 2×, daily cap on 4× (e.g. 3/day), compact models, no watermark, no ads.
- **US-041** Pro (sub or lifetime): 4× unlimited, all models incl. face restore & old-photo, batch > 3 at once, priority queue, HEIF/PNG output.
- **US-042** Restore purchase works offline-first via Play Billing cache; paywall never blocks viewing results already made.

## 4. Feature matrix

### P0 — MVP (must ship in v1.0)
| Feature | Notes |
|---|---|
| Import: picker, share-intent, files | US-001 |
| Compare slider viewer (zoom/pan) | US-002/003 |
| 2× & 4× upscaling, Photo + Anime presets | docs 03/04 |
| Denoise/deblur (light) | strength mapping |
| Face restoration (device-tier gated) | doc 03 §6 |
| Progress, cancel, single-image processing | US-013 |
| Batch queue (up to Pro limits) + foreground service | US-015 |
| Save/share with EXIF handling, GPS-strip | US-020/021 |
| Onboarding (privacy messaging) | US-030 |
| Model manager: bundled compact model + on-demand downloads | doc 03 §8 |
| Device tiering + benchmark probe | doc 08 |
| Settings: defaults, output folder, diagnostics | US-031 |
| Play Billing + paywall + restore purchase | doc 12 |
| Crashlytics/ANR reporting (opt-in analytics only) | doc 11 |
| Dark/light Material 3, English + 2–3 languages | doc 05 |

### P1 — v1.1–v1.3 (fast follows)
- Old-photo preset: scratch removal + fade correction (doc 03 §5.4)
- Colorization (B&W → color) as Pro feature
- "Enhance on share": quick-tile/shortcut (App Shortcuts + Quick Settings tile)
- Desktop-class output: > 64 MP with auto-tiling memory plan; print presets (300 DPI hints)
- Auto-batch suggestion: detect recently added blurry photos
- In-app result gallery ("Revived" tab) with re-run/edit recipe
- Widgets: "1-tap enhance last photo"
- More locales; RTL polish

### P2 — v2+ (doc 15)
- Video upscaling (frame pipeline) — the repo's original vision
- Cloud-hybrid optional mode (opt-in, clearly labeled)
- RAW/DNG pipeline, iOS, desktop

## 5. Non-functional requirements

| Area | Requirement |
|---|---|
| Performance | See doc 08 budgets: flagship 12→48 MP ≤ 15 s; mid ≤ 45 s; cold start ≤ 2 s |
| Memory | Never OOM on 3 GB RAM devices for ≤ 12 MP inputs; graceful guardrails (doc 04 §5) |
| Battery | Full 4× run on flagship ≤ 4% battery; adaptive tile size on thermal throttle |
| Storage | Base app ≤ 60 MB; models downloaded to app storage, removable from Settings |
| Compatibility | minSdk 26; arm64-v8a primary; x86_64 for emulator; 16 KB page-size compliant |
| Privacy | Zero network transmission of pixel data — verifiable (doc 11 §2 traffic test) |
| Offline | 100% of enhancement features work offline (only billing/model-download need network) |
| Accessibility | TalkBack full flow; contrast AA; 48 dp targets; slider has a11y actions |
| Stability | Crash-free ≥ 99.5%; ANR < 0.47%; OOM < 0.1% of sessions |
| Localization | Externalized strings from day 1; no hardcoded text; RTL-safe layouts |

## 6. Competitive snapshot

| App | Model | Offline? | Pricing model | Weak spot |
|---|---|---|---|---|
| Remini | Cloud AI | No | Sub (weekly!), credits | Cost, privacy, watermark |
| PhotoApp / EnhanceFox | Cloud | No | Sub + ads | Same |
| PixelUp | Cloud | No | Sub | Same |
| Snapseed | Classical filters | Yes | Free | No AI SR; steep UI |
| **PixelRevive** | **On-device AI** | **Yes** | **Freemium + lifetime** | Quality ceiling below cloud on worst inputs — mitigate via model presets + honest expectations UI |

**Positioning sentence:** "The AI photo enhancer that works on a plane, in a dead zone, and on your private photos — because your photos never leave your phone."

## 7. Analytics & success instrumentation (opt-in, doc 11)

| Event | Purpose |
|---|---|
| `enhance_started/completed/cancelled` (scale, preset, tier, duration, tiles) | Funnel + perf |
| `enhance_failed` (stage, error class) | Reliability |
| `model_download_started/succeeded/failed` | Delivery health |
| `paywall_viewed / purchase_started / purchased` | Revenue funnel |
| `benchmark_result` (anonymized device profile) | Tier tuning |

All analytics **opt-in** at onboarding; crash reporting separate toggle; never any image content or paths.
