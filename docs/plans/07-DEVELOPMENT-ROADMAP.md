# 07 — Development Roadmap (Phase 0 → Launch → v1.x)

Assumes **1–2 devs + part-time designer** (solo: multiply weeks ×1.6). 25 weeks to
production launch + buffer. Every phase has **exit criteria** — do not pass the gate
without them. Companion: `08-PERFORMANCE-OPTIMIZATION.md`, `09-TESTING-STRATEGY.md`.

---

## Milestone map

```
Wk  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26
    [P0][   P1   ][    P2     ][   P3    ][  P4  ][P5 ][ P6 ][  P7  ][P8][P9→LAUNCH]
    ▲setup        ▲first SR      ▲restore  ▲beta-ready           ▲hardening  ▲prod
```

## Phase 0 — Discovery & Setup (wk 1–2)
- Finalize doc-01 scope; trademark/name shortlist (doc 12 §8); buy domain.
- Repo scaffold per doc 06 (modules, CI, lint, Play Console shell, Firebase).
- ML spike: convert realesr-general-x4v3 → onnx + ncnn; run one 512² tile in a minimal APK on 2 devices.
- **License audit started** (doc 03 §7) — long-lead item.
✅ Exit: app skeleton runs; one AI tile processed on-device; CI green.

## Phase 1 — Foundations (wk 3–5)
- Photo Picker + share-intent import; ImageDetail viewer with zoom; compare-slider v1; design tokens (doc 05 §5).
- `:core:media`: decode/encode/EXIF-correct round-trip w/ golden tests (doc 04 §12).
- DeviceTier service v1; Settings shell; DataStore.
✅ Exit: import→view→compare loop pixel-perfect; EXIF fixtures 100% pass.

## Phase 2 — Core upscale pipeline (wk 6–9)  ← *highest risk*
- JNI bridge + both runtimes behind `:ml:api`; tiling engine + feather merge (doc 04 §4).
- Scale ×2/×4, presets Photo/Anime; progress/cancel; memory guardrails v1; benchmark probe.
- **Decision gate (ADR-1):** pick default runtime on device matrix (doc 08 §6).
✅ Exit: 12 MP → 48 MP < 45 s on mid-range; zero OOM on test corpus; cancel works mid-tile.

## Phase 3 — Restoration features (wk 10–12)
- Denoise strengths w/ NAFNet-light; face pipeline (ML Kit + GFPGAN-if-audited + fallback) (doc 03 §6).
- Output formats + SAF folders + GPS-strip; result screen save/share/wallpaper.
✅ Exit: face stage never blocks/fails a job; all doc-01 P0 features demo-able end-to-end.

## Phase 4 — Batch, queue, polish (wk 13–15)
- WorkManager queue + foreground service + notifications; partial-failure UX.
- Model manager UI (download/update/delete); onboarding final; empty/error states; haptics.
- Internal dogfood build to 5–10 testers via Play internal track.
✅ Exit: 20-photo batch survives backgrounding + process death; Play pre-report clean.

## Phase 5 — Performance & device tiers (wk 16–17)
- Delegate matrix tuning; thermal-adaptive tiling; cold-start and jank fixes; APK-size pass (doc 08).
- Benchmark harness on 6-device lab; tier defaults locked.
✅ Exit: doc-08 budgets met on all lab tiers; macrobenchmarks in CI.

## Phase 6 — Monetization (wk 18–19)
- Play Billing (monthly/yearly/lifetime), entitlement service, paywall, restore, daily-cap logic (doc 12).
- Analytics events (opt-in) + funnel dashboard.
✅ Exit: purchase flows pass license-testers on 2 tracks incl. offline restore.

## Phase 7 — Hardening & privacy (wk 20–22)
- Test pyramid to full (doc 09): golden sets, snapshot suite, E2E, Robolectric; nightly ASan.
- Privacy/security pass (doc 11): Data Safety form, policy pages, network-capture proof test.
- Accessibility audit fixes; localization drop (EN/ES/PT/HI).
✅ Exit: crash-free ≥ 99.5% in dogfood; a11y checklist 100%; zero image-data network calls verified.

## Phase 8 — Closed beta (wk 23–24)
- Play closed track, 100–500 users; rapid fix cycles; review-monitoring rota.
- ASO assets final: screenshots, feature graphic, listing A/B (doc 12 §5).
✅ Exit: ≥ 4.3★ beta sentiment; no P0 bugs; server-side kill switches (model manifest) live.

## Phase 9 — Launch (wk 25)
- Staged production rollout 5% → 20% → 50% → 100% (3-day ladder), Vitals watch (doc 10 §6).
- Launch marketing per doc 12 §6.

## Post-launch cadence (v1.x)
| Release | Theme | Highlights |
|---|---|---|
| v1.1 (+6 wk) | Old photos | scratch/fade preset, Revived tab, AR locale |
| v1.2 (+10 wk) | Colorize + reach | colorization (Pro), 3 locales, widgets |
| v1.3 (+14 wk) | Power users | recipes, >64 MP outputs, print presets |
| v2.0 | **Video upscaling** | see doc 15 |

## Standing rituals
- Weekly risk review against doc 13 (30 min).
- Every phase gate: update decision log (doc 00 §7) + re-estimate remaining phases.
- Release train: every 2 weeks after launch, trunk-based, feature flags for P1+ work.

## MVP cut-lines (if schedule slips)
1. Cut first: colorize (already P1), wallpaper action, dynamic color.
2. Then cut: HEIF output (JPEG+PNG only), batch limit to 10, anime preset ships as download later.
3. Never cut: compare slider, cancel/progress, EXIF correctness, privacy promise, license audit.
