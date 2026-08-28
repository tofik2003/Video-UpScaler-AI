# 13 — Risk Register & Contingencies

Reviewed at every phase gate (doc 07). Scale: Probability (P) & Impact (I) 1–5.
Owner = role. **Trigger** = pre-agreed condition that fires the contingency (no debate mid-crisis).

---

## 1. Top-5 risks (narrative)

1. **OOM crashes on real-world devices** — the classic image-app killer. Mitigated by budget law + strip streaming + derate ladder (docs 04 §5, 08 §4). We treat "zero OOM on golden corpus × lab devices" as a launch blocker.
2. **Model licensing blocks a headline feature** (face restore). Mitigated by audit-first (doc 03 §7) + fallback model path + own-finetune budget.
3. **Quality bar vs cloud competitors on worst inputs** — managed by honest expectation UI, presets, and the offline/privacy differentiator.
4. **Scope creep** (video! cloud! filters!) — the roadmap's cut-lines (doc 07) and non-goals (doc 00 §3) exist precisely for this.
5. **Solo-dev bus factor / burnout** — small scope, phase gates, and docs good enough for a successor.

## 2. Register

| ID | Risk | P | I | Owner | Mitigation | Trigger → Contingency |
|---|---|---|---|---|---|---|
| R-01 | OOM crash on low-RAM devices with big images | 4 | 5 | Android | MemoryBudget law, strip streaming, derate ladder, golden corpus | > 0.1% OOM sessions in beta → force 2× + tighter caps for that tier via remote flag |
| R-02 | GFPGAN license unusable commercially | 3 | 3 | ML | Audit first; fallback lite-face-SR; own finetune fund ($800, doc 14 §5) | Audit fails at Phase-3 gate → ship v1.0 with SR-only faces, face restore moves to v1.1 with own model |
| R-03 | Runtime (ncnn/ORT) driver bugs on specific GPUs | 4 | 3 | ML | Dual-engine abstraction (doc 02), crash-loop guard → CPU fallback | ≥ 2 GPU failures on a device family → auto-delegate-flag off via CDN kill switch |
| R-04 | Thermal throttling ruins UX on cheap devices | 3 | 3 | Android | Serial queue, adaptive tiles (doc 08 §8), tier caps | Thermal complaints > 1% sessions → lower LOW-tier defaults via flag |
| R-05 | Inference too slow on mid-range for 4× | 3 | 3 | ML | Compact models, int8 path, 2-stage ×2 | Phase-2 gate miss → 4× becomes HIGH-tier-only in v1 marketing |
| R-06 | Play policy surprise (AI-content rules, Data Safety interpretation) | 2 | 4 | Founder | Policy read at Phase 0/7; in-app result reporting; no data collection | Rejection → respond with data-flow diagram (doc 11 §1); escalate via Play support |
| R-07 | Scope creep / v1 never ships | 4 | 4 | Founder | Non-goals (doc 00), cut-lines (doc 07), 25-wk calendar | 4-wk slip → execute cut-line 1; 8-wk slip → cut-line 2 |
| R-08 | Trademark/name collision late | 2 | 2 | Founder | Search at Phase 0 before branding assets | Collision → swap name (only strings + listing affected; package independent) |
| R-09 | Model CDN cost/abuse (huge download loops) | 2 | 2 | Founder | R2 costs trivial; client caches + verifies; Range resume | Bill > $50/mo → move big models to PAD packs |
| R-10 | 1★ wave from expectation mismatch ("AI made my photo fake") | 3 | 3 | Founder/Growth | Honest onboarding, compare slider, denoise-first defaults | Rating < 4.0 in any rollout stage → pause ladder, patch onboarding copy + review-reply campaign |
| R-11 | Key-person unavailability (solo dev) | 2 | 4 | Founder | This doc pack + ADRs + recorded decisions | Freeze features, maintenance mode, open-source option |
| R-12 | Billing edge-cases (refunds, offline restore) | 2 | 2 | Android | Billing tested w/ license testers incl. refund flow (doc 09 §5) | Refund rate > 5% cohort → tighten paywall trigger copy |
| R-13 | Android 16 KB-page / future OS breakage | 2 | 3 | Android | CI test on 16 KB emulator; pin & update runtimes | Play warning → upgrade runtime AAR within 2 wks |
| R-14 | Conversion too low to sustain | 3 | 3 | Growth | Free tier tuned for reviews not revenue; lifetime card; ASO loop | < 1% at 10 K installs → pricing experiment + intro offers |
| R-15 | Legal/GDPR complaint about "AI processing biometric (face)" data | 1 | 4 | Founder | Faces processed transiently in memory, never stored/transmitted; documented in policy (doc 11) | Complaint → respond with architecture letter; optional "disable face stage" flag prominent |
| R-16 | Quality regression after model update | 2 | 4 | ML | Model semver + golden SSIM gates + staged manifest rollout (doc 03 §8, 09 §4) | Bad reports post-update → manifest rollback (instant, no app release) |

## 3. Watch-list (low probability, worth 1 line each)

- Google Play fee/monetization policy changes (30/15% tier) → model lifetime price accordingly.
- Qualcomm QNN EP licensing shifts → stay Vulkan/XNNPACK-default.
- On-device "AI enhancement" OS features (Galaxy/Google Photos free upscaling) → pivot messaging to *batch/restore/privacy/print niche*; deepen old-photo restoration.
- App-size creep from model inflation → hard 60 MB budget (doc 08 §9).

## 4. Burn-down ritual

- Phase gates (doc 07): re-score register, retire dead risks, add new ones ≥ P3×I3.
- Post-launch weekly: Vitals-driven review (crash/ANR/battery = R-01/03/04 sensors).
- Any trigger fired → written post-mortem in `docs/ADRs/` within 1 week (blameless, decision-focused).
